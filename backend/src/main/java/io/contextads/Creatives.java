package io.contextads;

import java.util.*;
import java.security.MessageDigest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
class Creatives {
    private final JdbcTemplate db;private final AssetStore store;private final org.springframework.transaction.support.TransactionTemplate tx;
    private final java.util.concurrent.Semaphore uploadSlots=new java.util.concurrent.Semaphore(2);
    Creatives(JdbcTemplate db,AssetStore store,org.springframework.transaction.PlatformTransactionManager manager){this.db=db;this.store=store;this.tx=new org.springframework.transaction.support.TransactionTemplate(manager);}
    record Draft(UUID campaignId,String headline,String description,String destination,UUID assetId){}
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    Map<String,Object> upload(String owner,byte[] input){
        if(!uploadSlots.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Upload capacity exhausted");
        try{
        var clean=ImageSafety.sanitize(input);String hash=hash(clean.png()),key="normalized/"+hash+".png";
        store.put(key,clean.png());UUID id=UUID.randomUUID();
        db.update("insert into creative_assets(id,owner_name,object_key,sha256,byte_size,width,height) values(?,?,?,?,?,?,?)",id,owner,key,hash,clean.png().length,clean.width(),clean.height());
        return Map.of("id",id,"sha256",hash,"bytes",clean.png().length,"width",clean.width(),"height",clean.height(),"type","image/png");
        }finally{uploadSlots.release();}
    }
    @Transactional public Map<String,Object> create(String owner,Draft draft){
        var campaigns=db.queryForList("select * from campaigns where id=? and owner_name=? for update",draft.campaignId(),owner);
        if(campaigns.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Campaign not found");
        if(draft.headline()==null||draft.headline().isBlank()||draft.headline().length()>160||draft.description()==null||draft.description().length()>500)throw new IllegalArgumentException("Headline 1–160 and description 0–500 characters required");
        if(draft.assetId()!=null&&db.queryForObject("select count(*) from creative_assets where id=? and owner_name=?",Integer.class,draft.assetId(),owner)!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Owned asset not found");
        UUID id=UUID.randomUUID();int version=db.queryForObject("select coalesce(max(version),0)+1 from creatives where campaign_id=?",Integer.class,draft.campaignId());
        db.update("insert into creatives(id,campaign_id,version,format,headline,description,destination,asset_id,status) values(?,?,?,?,?,?,?,?,'PENDING')",id,draft.campaignId(),version,draft.assetId()==null?"TEXT":"IMAGE",draft.headline(),draft.description(),Platform.destination(draft.destination()),draft.assetId());
        db.update("update campaigns set current_creative_id=?,approved=false where id=?",id,draft.campaignId());return Map.of("id",id,"version",version,"status","PENDING");
    }
    public Map<String,Object> review(UUID creativeOrCampaign,String action,String reason,String actor){
        if(reason==null||reason.isBlank()||reason.length()>2000)throw new IllegalArgumentException("Review reason required (maximum 2000 characters)");
        // Preserve the former campaign-ID review route while accepting genuine creative IDs.
        var references=db.queryForList("select campaign_id,id from creatives where id=? union all select id,current_creative_id from campaigns where id=?",creativeOrCampaign,creativeOrCampaign);
        if(references.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Creative not found");
        UUID campaign=(UUID)references.getFirst().get("campaign_id"),id=(UUID)references.getFirst().get("id");
        var pending=db.queryForMap("select * from creatives where id=?",id);
        if(creativeOrCampaign.equals(campaign)&&((Number)pending.get("version")).intValue()!=1)throw new IllegalArgumentException("Review the exact creative version ID, not the campaign alias");
        if(action.equals("approve")&&pending.get("asset_id")!=null)privateAsset((UUID)pending.get("asset_id"),null);
        return tx.execute(transaction->{
        var c=db.queryForMap("select * from campaigns where id=? for update",campaign);
        var creative=db.queryForMap("select * from creatives where id=? for update",id);
        String status=action.equals("approve")?"APPROVED":"REJECTED";
        db.update("update creatives set status=? where id=?",status,id);
        if(c.get("current_creative_id").equals(id))db.update("update campaigns set approved=? where id=?",status.equals("APPROVED"),campaign);
        db.update("insert into creative_reviews(creative_id,actor,action,reason) values(?,?,?,?)",id,actor,status,reason);
        return Map.of("id",id,"status",status,"version",creative.get("version"));
        });
    }
    byte[] approvedAsset(UUID id){
        var rows=db.queryForList("select a.* from creative_assets a where a.id=? and exists(select 1 from creatives c where c.asset_id=a.id and c.status='APPROVED')",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Approved asset not found");
        return read(rows.getFirst());
    }
    byte[] privateAsset(UUID id,String owner){
        var rows=owner==null?db.queryForList("select * from creative_assets where id=?",id):db.queryForList("select * from creative_assets where id=? and owner_name=?",id,owner);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Asset not found");return read(rows.getFirst());
    }
    private byte[] read(Map<String,Object> row){byte[] bytes=store.get((String)row.get("object_key"));
        if(bytes.length!=((Number)row.get("byte_size")).intValue()||!hash(bytes).equals(row.get("sha256")))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Asset integrity check failed");
        return bytes;
    }
}

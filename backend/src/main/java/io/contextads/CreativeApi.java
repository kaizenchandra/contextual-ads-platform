package io.contextads;

import java.util.*;
import java.security.Principal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/v1")
class CreativeApi {
    private final Creatives creatives;private final JdbcTemplate db;
    CreativeApi(Creatives creatives,JdbcTemplate db){this.creatives=creatives;this.db=db;}
    record Review(String reason){}
    @PostMapping(value="/creatives/assets",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    Map<String,Object> upload(@RequestParam("file")MultipartFile file,Principal user)throws java.io.IOException{return creatives.upload(user.getName(),file.getBytes());}
    @GetMapping("/creatives/assets") List<Map<String,Object>> assets(Principal user){return db.queryForList("select id,sha256,byte_size,width,height,created from creative_assets where owner_name=? order by created desc limit 100",user.getName());}
    @GetMapping(value="/creatives/assets/{id}",produces=MediaType.IMAGE_PNG_VALUE) ResponseEntity<byte[]> ownedAsset(@PathVariable UUID id,Principal user){return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").body(creatives.privateAsset(id,user.getName()));}
    @GetMapping(value="/admin/creative-assets/{id}",produces=MediaType.IMAGE_PNG_VALUE) ResponseEntity<byte[]> reviewAsset(@PathVariable UUID id){return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").body(creatives.privateAsset(id,null));}
    @PostMapping("/creatives") @ResponseStatus(HttpStatus.CREATED) Map<String,Object> create(@RequestBody Creatives.Draft draft,Principal user){return creatives.create(user.getName(),draft);}
    @GetMapping("/creatives") List<Map<String,Object>> list(Principal user){return db.queryForList("select cr.* from creatives cr join campaigns c on c.id=cr.campaign_id where c.owner_name=? order by cr.created desc,cr.id limit 100",user.getName());}
    @GetMapping("/creatives/{id}") Map<String,Object> get(@PathVariable UUID id,Principal user){return db.queryForList("select cr.* from creatives cr join campaigns c on c.id=cr.campaign_id where cr.id=? and c.owner_name=?",id,user.getName()).stream().findFirst().orElseThrow(()->new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,"Creative not found"));}
    @GetMapping("/admin/creatives/{id}/reviews") List<Map<String,Object>> history(@PathVariable UUID id){return db.queryForList("select * from creative_reviews where creative_id=? order by id desc limit 100",id);}
    @GetMapping("/admin/creatives") List<Map<String,Object>> review(){return db.queryForList("select cr.*,c.owner_name from creatives cr join campaigns c on c.id=cr.campaign_id order by cr.created desc,cr.id limit 100");}
    @PostMapping("/admin/creatives/{id}/{action:approve|reject}") Map<String,Object> approve(@PathVariable UUID id,@PathVariable String action,@RequestBody(required=false)Review review,Principal user){return creatives.review(id,action,(review==null||review.reason()==null)&&db.queryForObject("select count(*) from campaigns where id=?",Integer.class,id)==1?"Legacy initial campaign creative review":review==null?null:review.reason(),user.getName());}
    @GetMapping(value="/ads/assets/{id}",produces=MediaType.IMAGE_PNG_VALUE) ResponseEntity<byte[]> asset(@PathVariable UUID id){return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Cross-Origin-Resource-Policy","cross-origin").body(creatives.approvedAsset(id));}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ProblemDetail> bad(IllegalArgumentException error){return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,error.getMessage()));}
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class) ResponseEntity<ProblemDetail> big(){return ResponseEntity.status(413).body(ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,"Maximum upload is 2 MiB"));}
}

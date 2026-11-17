package io.contextads;

import java.util.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.server.ResponseStatusException;

@Component
class Rendering {
    private final JdbcTemplate db;private final Tokens tokens;
    final String origin;private final String console;
    Rendering(JdbcTemplate db,Tokens tokens,@Value("${ads.render-origin:http://localhost:8086}")String origin,@Value("${ads.console-origin:http://localhost:3006}")String console){this.db=db;this.tokens=tokens;this.origin=origin(origin);this.console=origin(console);}
    static String origin(String value){var u=URI.create(value);if(!Set.of("https","http").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getRawQuery()!=null||u.getFragment()!=null||!(u.getPath().isEmpty()||u.getPath().equals("/")))throw new IllegalArgumentException("Configured rendering origin must be an absolute HTTP(S) origin");return value.endsWith("/")?value.substring(0,value.length()-1):value;}
    String url(UUID decision){return origin+"/api/v1/ads/render/"+tokens.sign(decision,"render");}
    ResponseEntity<String> frame(String token){
        UUID id=tokens.verify(token,"render");
        var rows=db.queryForList("""
            select d.*,cr.asset_id,w.domain from decisions d join campaigns c on c.id=d.campaign_id
            join creatives cr on cr.id=d.creative_id and cr.campaign_id=c.id join pages p on p.id=d.page_id
            join publisher_websites w on w.id=p.website_id join domain_ownership o on o.website_id=w.id
            join publisher_placements s on s.id=d.placement_id and s.website_id=w.id
            where d.id=? and d.expires>clock_timestamp() and d.state<>'RELEASED'
            and cr.status='APPROVED' and c.current_creative_id=cr.id and c.approved=true and c.status='ACTIVE'
            and w.verified_until>clock_timestamp() and s.status='ACTIVE'
            """,id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.GONE,"Ad decision is expired, revoked or no longer eligible");
        var d=rows.getFirst();String nonce=UUID.randomUUID().toString().replace("-","");
        String headline=HtmlUtils.htmlEscape((String)d.get("creative_headline"));
        String description=HtmlUtils.htmlEscape((String)d.get("creative_description"));
        String image=d.get("asset_id")==null?"":"<img id=media alt=\"Sponsored creative\" src=\""+origin+"/api/v1/ads/assets/"+d.get("asset_id")+"\">";
        String html="<!doctype html><html lang=en><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>Advertisement</title>"
            +"<style nonce=\""+nonce+"\">body{margin:0;font:16px system-ui;background:#f0f5e5;color:#183b30}article{padding:20px;border:1px solid #b4c99d}strong{font-size:12px;text-transform:uppercase}h2{font-size:22px;margin:12px 0}img{max-width:100%;max-height:150px;object-fit:contain}a{color:#174d35}p{line-height:1.5}</style>"
            +"<article id=ad><strong>Sponsored · Advertisement</strong>"+image+"<h2>"+headline+"</h2><p>"+description+"</p><a target=_blank rel=\"sponsored noopener noreferrer\" href=\""+origin+"/api/v1/ads/click/"+tokens.sign(id,"click")+"\">Visit advertiser</a><p><small>Paid placement. Client signals do not prove human attention.</small></p></article>"
            +"<script nonce=\""+nonce+"\">"+measurement(origin+"/api/v1/ads/impressions",tokens.sign(id,"impression"))+"</script></html>";
        String csp="default-src 'none'; base-uri 'none'; form-action 'none'; object-src 'none'; img-src "+origin+"; connect-src "+origin+"; script-src 'nonce-"+nonce+"'; style-src 'nonce-"+nonce+"'; frame-ancestors https://"+d.get("domain")+" "+console+"; sandbox allow-scripts allow-popups allow-popups-to-escape-sandbox";
        return ResponseEntity.ok().contentType(new MediaType("text","html",StandardCharsets.UTF_8)).header("Cache-Control","no-store").header("Referrer-Policy","no-referrer").header("Content-Security-Policy",csp).header("Permissions-Policy","camera=(), microphone=(), geolocation=()").body(html);
    }
    private static String measurement(String endpoint,String token){
        // Values interpolated here are fixed trusted origins and HMAC URL-safe tokens, never creative strings.
        return """
            (()=>{let visible=false,ready=!document.getElementById('media'),timer=null,sent=false,attempts=0;
            const reset=()=>{if(timer)clearTimeout(timer);timer=null;};
            const send=()=>{if(sent||attempts>=3)return;attempts++;fetch('ENDPOINT',{method:'POST',credentials:'omit',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:'TOKEN'})}).then(r=>{if(r.ok)sent=true;else if(attempts<3)setTimeout(update,1500);}).catch(()=>{if(attempts<3)setTimeout(update,1500);});};
            const reportReady=()=>parent.postMessage({type:'contextads.ready',version:1},'*');
            const update=()=>{reset();if(!sent&&ready&&visible&&document.visibilityState==='visible')timer=setTimeout(send,1000);};
            const observer=new IntersectionObserver(([e])=>{visible=e.intersectionRatio>=.5;update();},{threshold:[0,.5,1]});observer.observe(document.getElementById('ad'));
            document.addEventListener('visibilitychange',update);window.addEventListener('pagehide',reset);
            const image=document.getElementById('media');if(image){image.addEventListener('load',()=>{ready=true;reportReady();update();});image.addEventListener('error',()=>{ready=false;reset();});if(image.complete&&image.naturalWidth>0){ready=true;reportReady();update();}}else reportReady();
            })();
            """.replace("ENDPOINT",endpoint).replace("TOKEN",token);
    }
}

@RestController
class RenderingApi {
    private final Rendering rendering;RenderingApi(Rendering rendering){this.rendering=rendering;}
    @GetMapping("/api/v1/ads/render/{token}") ResponseEntity<String> frame(@PathVariable String token){return rendering.frame(token);}
    @GetMapping(value="/sdk/v1.js",produces="text/javascript") ResponseEntity<String> sdk(){
        String script="""
            (()=>{'use strict';const source=document.currentScript;if(!source)return;const allowed=new URL(source.src).origin;const active=new WeakMap();
            const mount=(container,options)=>{if(!(container instanceof HTMLElement))throw new Error('Container required');const url=new URL(options.renderUrl);if(url.origin!==allowed||!/^\\/api\\/v1\\/ads\\/render\\/[0-9a-f-]{36}\\.[A-Za-z0-9_-]+$/.test(url.pathname)||url.search||url.hash)throw new Error('Invalid rendering capability');
            const frame=document.createElement('iframe');frame.title='Advertisement';frame.sandbox='allow-scripts allow-popups allow-popups-to-escape-sandbox';frame.referrerPolicy='no-referrer';frame.loading='lazy';frame.src=url.href;frame.width='100%';frame.height='360';frame.style.border='0';if(active.has(container))active.get(container)();const label=document.createElement('strong');label.textContent='Sponsored';const status=document.createElement('p');status.textContent='Loading advertisement…';container.replaceChildren(label,status,frame);let timer;
            const dispose=()=>{clearTimeout(timer);window.removeEventListener('message',ready);};const ready=e=>{if(e.source===frame.contentWindow&&e.origin==='null'&&e.data&&e.data.type==='contextads.ready'&&e.data.version===1){status.hidden=true;dispose();}};window.addEventListener('message',ready);timer=setTimeout(()=>{status.textContent='Advertisement unavailable';frame.remove();dispose();},8000);active.set(container,dispose);return frame;};
            window.ContextualAds=Object.freeze({mount});document.querySelectorAll('[data-contextual-ad]').forEach((node,i)=>{if(i<50&&node.dataset.renderUrl)mount(node,{renderUrl:node.dataset.renderUrl});});})();
            """;
        return ResponseEntity.ok().header("Cache-Control","public,max-age=3600").header("X-Content-Type-Options","nosniff").body(script);
    }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ProblemDetail> bad(IllegalArgumentException error){return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,error.getMessage()));}
}

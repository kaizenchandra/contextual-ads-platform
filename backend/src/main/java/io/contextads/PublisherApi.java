package io.contextads;

import java.util.*;
import java.security.Principal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/publishers")
class PublisherApi {
    private final Publishers publishers;
    private final JdbcTemplate db;

    PublisherApi(Publishers publishers, JdbcTemplate db) {
        this.publishers = publishers;
        this.db = db;
    }

    @PostMapping("/websites")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> register(@RequestBody Website website, Principal user) {
        return publishers.register(website.domain(), user.getName());
    }

    @GetMapping("/websites")
    List<Map<String, Object>> websites(Principal user) {
        return db.queryForList("select id,domain,verified_until,challenge_expires from publisher_websites where owner_name=? order by created desc limit 100", user.getName());
    }

    @PostMapping("/websites/{id}/verify")
    Map<String, Object> verify(@PathVariable UUID id, Principal user) {
        return publishers.verify(id, user.getName());
    }

    @PostMapping("/websites/{id}/revoke")
    Map<String, Object> revoke(@PathVariable UUID id, Principal user) {
        publishers.owned(id, user.getName());
        db.update("update publisher_websites set verified_until=null where id=? and owner_name=?", id, user.getName());
        return Map.of("revoked", true);
    }

    @PostMapping("/placements")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> placement(@RequestBody Placement placement, Principal user) {
        publishers.active(placement.websiteId(), user.getName());
        if (placement.name() == null || placement.name().isBlank() || placement.name().length() > 100)
            throw new IllegalArgumentException("Placement name required, maximum 100 characters");
        UUID id = UUID.randomUUID();
        db.update("insert into publisher_placements(id,website_id,name) values(?,?,?)", id, placement.websiteId(), placement.name());
        return Map.of("id", id);
    }

    @GetMapping("/placements")
    List<Map<String, Object>> placements(Principal user) {
        return db.queryForList("select p.* from publisher_placements p join publisher_websites w on w.id=p.website_id where w.owner_name=? order by p.id limit 100", user.getName());
    }

    @PostMapping("/placements/{id}/pause")
    Map<String, Object> pause(@PathVariable UUID id, Principal user) {
        int count = db.update("update publisher_placements p set status='PAUSED' from publisher_websites w where p.website_id=w.id and p.id=? and w.owner_name=?", id, user.getName());
        if (count != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of("paused", true);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> bad(IllegalArgumentException e) {
        return org.springframework.http.ResponseEntity.badRequest().body(org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    record Website(String domain) {
    }

    record Placement(UUID websiteId, String name) {
    }
}

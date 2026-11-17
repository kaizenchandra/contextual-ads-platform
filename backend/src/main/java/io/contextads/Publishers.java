package io.contextads;

import java.net.*;
import java.time.*;
import java.util.*;
import java.security.SecureRandom;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
class Publishers {
    private final JdbcTemplate db;
    private final DnsTxtResolver dns;
    private final TransactionTemplate tx;

    Publishers(JdbcTemplate db, DnsTxtResolver dns, PlatformTransactionManager manager) {
        this.db = db;
        this.dns = dns;
        this.tx = new TransactionTemplate(manager);
    }

    static String domain(String input) {
        if (input == null || input.length() > 253) throw new IllegalArgumentException("Invalid domain");
        String domain;
        try {
            domain = IDN.toASCII(input, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid domain");
        }
        if (!domain.matches("(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")
                || domain.matches(".*\\.(localhost|local|internal|invalid|test|example|onion)"))
            throw new IllegalArgumentException("Public DNS domain required");
        return domain;
    }

    Map<String, Object> register(String input, String owner) {
        String domain = domain(input);
        UUID id = UUID.randomUUID();
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("insert into publisher_websites(id,owner_name,domain,verification_token,challenge_expires) values(?,?,?,?,now()+interval '24 hours') on conflict(owner_name,domain) do update set verification_token=excluded.verification_token,challenge_expires=excluded.challenge_expires", id, owner, domain, token);
        var site = db.queryForMap("select * from publisher_websites where owner_name=? and domain=?", owner, domain);
        return Map.of("id", site.get("id"), "domain", domain, "recordName", "_contextads-verification." + domain, "recordValue", "contextads=" + token, "challengeExpires", site.get("challenge_expires"));
    }

    Map<String, Object> owned(UUID id, String owner) {
        return db.queryForList("select * from publisher_websites where id=? and owner_name=?", id, owner).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Website not found"));
    }

    Map<String, Object> verify(UUID id, String owner) {
        var site = owned(id, owner);
        // Network I/O is outside the transaction. Recheck the exact challenge under lock.
        String challenge = (String) site.get("verification_token");
        if (!dns.lookup("_contextads-verification." + site.get("domain")).contains("contextads=" + challenge))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DNS challenge not found");
        return tx.execute(status -> {
            var locked = db.queryForMap("select *,challenge_expires>now() as challenge_valid from publisher_websites where id=? for update", id);
            if (!locked.get("verification_token").equals(challenge) || !Boolean.TRUE.equals(locked.get("challenge_valid")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Challenge expired or rotated");
            String domain = (String) locked.get("domain");
            db.update("insert into domain_ownership(domain,website_id) values(?,?) on conflict do nothing", domain, id);
            var claim = db.queryForMap("select * from domain_ownership where domain=? for update", domain);
            if (!claim.get("website_id").equals(id)) {
                boolean live = Boolean.TRUE.equals(db.queryForObject("select verified_until>now() from publisher_websites where id=?", Boolean.class, claim.get("website_id")));
                if (live)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Domain already verified by another publisher");
                db.update("update domain_ownership set website_id=? where domain=?", id, domain);
            }
            db.update("update publisher_websites set verified_until=now()+interval '30 days' where id=?", id);
            return Map.<String, Object>of("verified", true, "websiteId", id);
        });
    }

    void active(UUID id, String owner) {
        if (db.queryForObject("select count(*) from publisher_websites w join domain_ownership o on o.website_id=w.id where w.id=? and w.owner_name=? and w.verified_until>now()", Integer.class, id, owner) != 1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A verified website owned by this publisher is required");
    }

    String pageUrl(UUID website, String owner, String input) {
        active(website, owner);
        var site = owned(website, owner);
        URI uri;
        try {
            uri = URI.create(input);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid page URL");
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !domain(uri.getHost()).equals(site.get("domain")))
            throw new IllegalArgumentException("Page URL must use the exact verified HTTPS domain, without query or fragment");
        return uri.toASCIIString();
    }
}

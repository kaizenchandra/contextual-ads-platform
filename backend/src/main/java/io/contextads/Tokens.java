package io.contextads;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class Tokens {
    private final byte[] key;

    Tokens(@Value("${ads.secret}") String s) {
        if (s.length() < 32) throw new IllegalArgumentException("Signing key too short");
        key = s.getBytes(StandardCharsets.UTF_8);
    }

    String sign(UUID id, String kind) {
        String p = id + ":" + kind;
        return id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(p));
    }

    UUID verify(String token, String kind) {
        try {
            var s = token.split("[.]");
            UUID id = UUID.fromString(s[0]);
            if (s.length != 2 || !MessageDigest.isEqual(mac(id + ":" + kind), Base64.getUrlDecoder().decode(s[1])))
                throw new IllegalArgumentException();
            return id;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid event token");
        }
    }

    private byte[] mac(String s) {
        try {
            var m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

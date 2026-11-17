package io.contextads;

import java.util.*;
import javax.naming.directory.*;

import org.springframework.stereotype.Component;

interface DnsTxtResolver {
    List<String> lookup(String name);
}

@Component
class SystemDnsTxtResolver implements DnsTxtResolver {
    @Override
    public List<String> lookup(String name) {
        var env = new Hashtable<String, String>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        DirContext context = null;
        try {
            context = new InitialDirContext(env);
            var attr = context.getAttributes(name, new String[]{"TXT"}).get("TXT");
            if (attr == null) return List.of();
            var values = new ArrayList<String>();
            for (int i = 0; i < Math.min(attr.size(), 32); i++) {
                // JNDI returns quoted TXT strings; challenges fit in a single string.
                String value = attr.get(i).toString();
                if (value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
                values.add(value);
            }
            return values;
        } catch (javax.naming.NameNotFoundException e) {
            return List.of();
        } catch (javax.naming.NamingException e) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "DNS unavailable; retry verification");
        } finally {
            if (context != null) try {
                context.close();
            } catch (javax.naming.NamingException ignored) {
            }
        }
    }
}

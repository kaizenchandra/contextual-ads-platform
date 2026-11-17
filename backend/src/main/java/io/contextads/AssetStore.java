package io.contextads;

import java.net.URI;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.retry.RetryPolicy;

@Component
class AssetStore implements AutoCloseable {
    private final S3Client client;private final String bucket;
    AssetStore(@Value("${ads.assets.endpoint:http://localhost:9006}")String endpoint,@Value("${ads.assets.key:local-assets}")String key,@Value("${ads.assets.secret:local-assets-secret-change-me}")String secret,@Value("${ads.assets.bucket:creative-assets}")String bucket){
        this.bucket=bucket;
        client=S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key,secret)))
                .httpClientBuilder(ApacheHttpClient.builder().maxConnections(4).connectionTimeout(Duration.ofSeconds(1)).socketTimeout(Duration.ofSeconds(2)))
                .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(5)).apiCallAttemptTimeout(Duration.ofSeconds(3)).retryPolicy(RetryPolicy.none())).build();
    }
    void put(String key,byte[] bytes){
        try{
            try{client.headBucket(b->b.bucket(bucket));}catch(S3Exception missing){if(missing.statusCode()!=404)throw missing;try{client.createBucket(b->b.bucket(bucket));}catch(S3Exception race){if(race.statusCode()!=409)throw race;}}
            client.putObject(b->b.bucket(bucket).key(key).contentType("image/png"),RequestBody.fromBytes(bytes));
        }catch(Exception e){throw unavailable();}
    }
    byte[] get(String key){
        try(var input=client.getObject(b->b.bucket(bucket).key(key))){
            if(input.response().contentLength()>5*1024*1024)throw unavailable();
            byte[] bytes=input.readNBytes(5*1024*1024+1);if(bytes.length>5*1024*1024)throw unavailable();return bytes;
        }catch(Exception e){throw unavailable();}
    }
    private static org.springframework.web.server.ResponseStatusException unavailable(){return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Approved asset storage unavailable");}
    public void close(){client.close();}
}

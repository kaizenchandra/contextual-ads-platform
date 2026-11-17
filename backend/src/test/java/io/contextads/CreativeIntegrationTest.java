package io.contextads;

import java.util.*;import java.time.*;import java.sql.Timestamp;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.*;import org.springframework.test.context.bean.override.mockito.MockitoBean;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.security.oauth2.jwt.*;import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.testcontainers.containers.*;import org.testcontainers.junit.jupiter.*;import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.*;import software.amazon.awssdk.regions.Region;import software.amazon.awssdk.services.s3.*;

@SpringBootTest(properties="ads.events.enabled=false") @AutoConfigureMockMvc @Testcontainers
class CreativeIntegrationTest {
 @org.testcontainers.junit.jupiter.Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.6");
 @org.testcontainers.junit.jupiter.Container static GenericContainer<?> minio=new GenericContainer<>("contextual-ads-minio:local").withEnv("MINIO_ROOT_USER","local-assets").withEnv("MINIO_ROOT_PASSWORD","local-assets-secret-change-me").withCommand("server","/data").withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/ready"));
 @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);r.add("ads.assets.endpoint",()->"http://"+minio.getHost()+":"+minio.getMappedPort(9000));}
 @Autowired JdbcTemplate db;@Autowired Creatives creatives;@Autowired Platform platform;@Autowired Tokens tokens;@Autowired Rendering rendering;@Autowired MockMvc mvc;
 @MockitoBean JwtDecoder decoder;
 UUID campaign,website,page,placement;
 @BeforeEach void fixture(){
  db.execute("truncate campaigns,pages,outbox,publisher_websites,domain_ownership,publisher_placements,creative_assets cascade");
  campaign=UUID.randomUUID();website=UUID.randomUUID();page=UUID.randomUUID();placement=UUID.randomUUID();
  db.update("insert into campaigns(id,owner_name,name,category,headline,destination,budget,bid,daily_budget,approved,status) values(?,'advertiser','image','technology.java','Initial Java','https://example.org',100,5,100,true,'ACTIVE')",campaign);
  db.update("insert into publisher_websites(id,owner_name,domain,verification_token,challenge_expires,verified_until) values(?,'publisher','publisher.example','fixture',now()+interval '1 hour',now()+interval '1 day')",website);
  db.update("insert into domain_ownership(domain,website_id) values('publisher.example',?)",website);
  db.update("insert into publisher_placements(id,website_id,name) values(?,?,'native')",placement,website);
  db.update("insert into pages(id,owner_name,title,category,confidence,keywords,safe,website_id,page_url) values(?,'publisher','Java','technology.java',1,'java',true,?,'https://publisher.example/java')",page,website);
 }
 UUID uploaded()throws Exception{return (UUID)creatives.upload("advertiser",ImageSafetyTest.png(40,20)).get("id");}
 UUID draft(UUID asset,String headline){return (UUID)creatives.create("advertiser",new Creatives.Draft(campaign,headline,"Developer tools","https://example.org/java",asset)).get("id");}
 String token(Map<String,Object> d){return tokens.sign((UUID)d.get("id"),"render");}
 @Test void pendingAssetIsPrivateAndApprovalEnablesVersionedImageDecision()throws Exception{
  UUID asset=uploaded(),id=draft(asset,"Learn Java safely");
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->creatives.approvedAsset(asset));
  assertEquals(false,platform.decide(page,placement,"publisher").get("filled"));
  creatives.review(id,"approve","Image pixels and destination inspected","admin");
  assertNotNull(creatives.approvedAsset(asset));var d=platform.decide(page,placement,"publisher");
  assertEquals("IMAGE",d.get("format"));assertEquals(id,d.get("creativeId"));assertTrue(((String)d.get("renderUrl")).contains("/ads/render/"));
  var html=rendering.frame(token(d));assertTrue(html.getBody().contains("Sponsored"));assertTrue(html.getBody().contains("/ads/assets/"+asset));
 }
 @Test void rejectionRevokesNewDecisionsFramesAndSignalsWithoutOverspending()throws Exception{
  UUID id=draft(uploaded(),"Java product");creatives.review(id,"approve","Approved reviewed product image","admin");var d=platform.decide(page,placement,"publisher");
  creatives.review(id,"reject","Landing page was rejected after review","admin");
  assertEquals(false,platform.decide(page,placement,"publisher").get("filled"));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->rendering.frame(token(d)));
  assertThrows(IllegalArgumentException.class,()->platform.event((String)d.get("impressionToken"),"impression"));
  assertEquals(5,db.queryForObject("select reserved from campaigns where id=?",Long.class,campaign));assertEquals(0,db.queryForObject("select spent from campaigns where id=?",Long.class,campaign));
 }
 @Test void newVersionDoesNotInheritApprovalAndOldDecisionDestinationCannotChange(){
  var d=platform.decide(page,placement,"publisher");UUID old=(UUID)d.get("creativeId");
  UUID next=draft(null,"New Java product");
  assertThrows(IllegalArgumentException.class,()->creatives.review(campaign,"approve","A stale campaign shortcut must not approve a changed version","admin"));
  assertEquals(false,platform.decide(page,placement,"publisher").get("filled"));
  creatives.review(old,"approve","Retrying an old version must not activate new content","admin");assertEquals(false,platform.decide(page,placement,"publisher").get("filled"));
  creatives.review(next,"approve","New destination and text separately reviewed","admin");
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->rendering.frame(token(d)));
  assertEquals("https://example.org",db.queryForObject("select creative_destination from decisions where id=?",String.class,d.get("id")));
  assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update creatives set destination='https://evil.org' where id=?",next));
 }
 @Test void renderingEscapesCreativeTextAndUsesOpaqueSandboxWithStrictCsp()throws Exception{
  UUID id=draft(null,"<script>parent.attack()</script>");creatives.review(id,"approve","Literal script-looking text verified as text","admin");var d=platform.decide(page,placement,"publisher");
  var result=mvc.perform(get("/api/v1/ads/render/"+token(d))).andExpect(status().isOk()).andExpect(header().doesNotExist("X-Frame-Options")).andReturn().getResponse();
  assertTrue(result.getContentAsString().contains("&lt;script&gt;"));assertFalse(result.getContentAsString().contains("<script>parent.attack"));
  String csp=result.getHeader("Content-Security-Policy");assertTrue(csp.contains("sandbox allow-scripts"));assertFalse(csp.contains("allow-same-origin"));assertFalse(csp.contains("unsafe-inline"));assertTrue(csp.contains("frame-ancestors https://publisher.example http://localhost:3006"));
  mvc.perform(get("/api/v1/ads/render/"+tokens.sign((UUID)d.get("id"),"click"))).andExpect(status().isBadRequest());
 }
 @Test void expiredOrRevokedWebsiteCannotRender(){var d=platform.decide(page,placement,"publisher");db.update("update publisher_websites set verified_until=now()-interval '1 day' where id=?",website);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->rendering.frame(token(d)));}
 @Test void uploadAndCreativeAccessAreTenantIsolatedAndAdminOnlyReview()throws Exception{
  UUID asset=uploaded();assertThrows(org.springframework.web.server.ResponseStatusException.class,()->creatives.create("other",new Creatives.Draft(campaign,"x","","https://example.org",asset)));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->creatives.privateAsset(asset,"other"));
  when(decoder.decode("advertiser")).thenReturn(Jwt.withTokenValue("advertiser").header("alg","RS256").subject("advertiser").claim("resource_access",Map.of("contextads-api",Map.of("roles",List.of("ADVERTISER")))).build());
  UUID foreign=UUID.randomUUID();db.update("insert into campaigns(id,owner_name,name,category,headline,destination,budget,bid,daily_budget) values(?,'other','foreign','technology.java','Other','https://example.org',100,5,100)",foreign);
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->creatives.create("other",new Creatives.Draft(foreign,"Not my asset","","https://example.org",asset)));
  UUID id=draft(asset,"Java image");mvc.perform(post("/api/v1/admin/creatives/"+id+"/approve").header("Authorization","Bearer advertiser").contentType("application/json").content("{\"reason\":\"Unauthorized approval\"}")).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/ads/assets/"+asset)).andExpect(status().isNotFound());
 }
 @Test void concurrentVersionsAreUniqueAndNeverInheritApproval()throws Exception{
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(8)){
   var work=new ArrayList<java.util.concurrent.Callable<Map<String,Object>>>();for(int i=0;i<16;i++){int n=i;work.add(()->creatives.create("advertiser",new Creatives.Draft(campaign,"Version "+n,"","https://example.org",null)));}
   var versions=new HashSet<Integer>();for(var future:pool.invokeAll(work))versions.add(((Number)future.get().get("version")).intValue());assertEquals(16,versions.size());assertTrue(versions.contains(17));
  }
  assertEquals(false,platform.decide(page,placement,"publisher").get("filled"));assertEquals(0,db.queryForObject("select reserved from campaigns where id=?",Long.class,campaign));
 }
 @Test void objectStoreOutageCannotApproveAndRecoversWithoutDuplicateVersion()throws Exception{
  UUID id=draft(uploaded(),"Java image");minio.getDockerClient().pauseContainerCmd(minio.getContainerId()).exec();
  try{assertThrows(org.springframework.web.server.ResponseStatusException.class,()->creatives.review(id,"approve","Approved image pending storage verification","admin"));assertEquals("PENDING",db.queryForObject("select status from creatives where id=?",String.class,id));}
  finally{minio.getDockerClient().unpauseContainerCmd(minio.getContainerId()).exec();}
  creatives.review(id,"approve","Storage recovered and exact image reviewed","admin");assertEquals(true,platform.decide(page,placement,"publisher").get("filled"));
 }
 @Test void tamperedOrMissingObjectFailsIntegrityCheck()throws Exception{
  UUID asset=uploaded(),id=draft(asset,"Java image");creatives.review(id,"approve","Approved exact normalized image bytes","admin");
  try(var s3=S3Client.builder().region(Region.US_EAST_1).endpointOverride(java.net.URI.create("http://"+minio.getHost()+":"+minio.getMappedPort(9000))).forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local-assets","local-assets-secret-change-me"))).build()){
   String key=db.queryForObject("select object_key from creative_assets where id=?",String.class,asset);s3.putObject(b->b.bucket("creative-assets").key(key),software.amazon.awssdk.core.sync.RequestBody.fromBytes("tampered".getBytes()));
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->creatives.approvedAsset(asset));s3.deleteObject(b->b.bucket("creative-assets").key(key));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->creatives.approvedAsset(asset));
  }
 }
 @Test void corsOnlyAllowsPublicTokenMeasurementNotCredentialedManagement()throws Exception{
  mvc.perform(options("/api/v1/ads/impressions").header("Origin","null").header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","Content-Type")).andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","*")).andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
  mvc.perform(options("/api/v1/campaigns").header("Origin","https://evil.example").header("Access-Control-Request-Method","POST")).andExpect(status().isForbidden());
 }
}

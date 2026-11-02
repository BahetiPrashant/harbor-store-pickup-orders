package io.orderflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.scheduling.annotation.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration
@EnableScheduling
class SchedulingConfig {}

@RestController
class Api {
    static final String BAD_HINT = "Check the spelling and try again. Passwords are case sensitive.";
    static final String CART_HINT = "Sign in, then try that again. Your cart stays in this browser.";
    static final String FORBIDDEN_HINT = "Customers place orders and cancel them while pending. Operators move an order onward. Sign in with the account that matches the action.";
    private static final Pattern ARGON2ID_HASH=Pattern.compile("^\\$argon2id\\$v=19\\$m=(\\d+),t=(\\d+),p=(\\d+)\\$([A-Za-z0-9+/]+)\\$([A-Za-z0-9+/]+)$");
    private static final java.time.format.DateTimeFormatter ORDER_TIMESTAMP=new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Map<String,Object>> users = new ConcurrentHashMap<>();
    private final OrderRepository orders;
    private final Map<String, java.util.ArrayDeque<Long>> rate = new ConcurrentHashMap<>();
    private long lastRateCleanup;
    private List<Map<String,Object>> catalog = List.of();
    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    @Value("${orderflow.seed-users:true}") boolean seed;
    @Value("${orderflow.seed-password:local-dev-password}") String seedPassword;
    @Value("${orderflow.users-file:}") String usersFile;
    @Value("${orderflow.jwt-secret}") String jwtSecret;
    @Value("${orderflow.internal-token}") String internalToken;
    @Value("${spring.datasource.password:}") String databasePassword;
    @Value("${spring.profiles.active:}") String activeProfiles;
    @Value("${orderflow.promote-delay-ms:30000}") long delay;
    @Value("${orderflow.embedded-promote:true}") boolean embedded;
    @Value("${orderflow.promote-interval-ms:15000}") long interval;

    @Autowired Api(OrderRepository orders) { this.orders=orders; }
    Api() { this(new InMemoryOrderRepository()); }

    @PostConstruct void init() throws Exception {
        catalog = json.readValue(getClass().getClassLoader().getResourceAsStream("catalog/catalog.json"), new TypeReference<>(){});
        if (usersFile!=null && !usersFile.isBlank()) {
            Path path=Path.of(usersFile);
            if (!Files.isRegularFile(path)) throw new IllegalStateException("Configured user file does not exist");
            List<Map<String,Object>> configuredUsers=json.readValue(path.toFile(),new TypeReference<>(){});
            configuredUsers.forEach(this::putConfiguredUser);
        }
        if (seed && seedPassword != null && !seedPassword.isBlank()) {
            putUser("ada@harbor.example", "user-ada", "CUSTOMER", "cust-ada", "Ada Lovelace");
            putUser("ops@harbor.example", "user-ops", "OPERATOR", null, "Floor operator");
        }
        if (productionProfile() &&
            (seed || weak(jwtSecret) || weak(internalToken) || weak(databasePassword) || users.isEmpty())) {
            throw new IllegalStateException("Unsafe or incomplete production configuration");
        }
    }
    private boolean productionProfile() {
        return "prod".equalsIgnoreCase(System.getenv("ORDERFLOW_ENV")) ||
            Arrays.stream(activeProfiles.split(",")).anyMatch("prod"::equalsIgnoreCase);
    }
    private static boolean weak(String s) {
        if (s==null || s.isBlank() || s.getBytes(StandardCharsets.UTF_8).length<32) return true;
        String normalized=s.toLowerCase(Locale.ROOT);
        return Set.of("change-me", "local-dev-jwt-secret-at-least-32-bytes", "local-dev-internal-token-32bytes",
            "replace-this-with-a-long-production-jwt-secret", "replace-this-with-a-long-production-internal-token").contains(normalized) ||
            normalized.chars().distinct().count()<8;
    }
    private void putUser(String email, String id, String role, String customerId, String name) {
        var u = new HashMap<String,Object>(); u.put("email", email); u.put("id", id); u.put("role", role); u.put("customerId", customerId); u.put("customerName", name); u.put("password", encoder.encode(seedPassword)); users.put(email, u);
    }
    private void putConfiguredUser(Map<String,Object> row) {
        String email=String.valueOf(row.getOrDefault("email"," ")).trim().toLowerCase(Locale.ROOT);
        String id=String.valueOf(row.getOrDefault("id"," ")).trim();
        String role=String.valueOf(row.getOrDefault("role"," ")).trim();
        String passwordHash=String.valueOf(row.getOrDefault("passwordHash"," ")).trim();
        Object customerId=row.get("customerId");
        Object customerName=row.get("customerName");
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || id.isBlank() ||
            !Set.of("CUSTOMER","OPERATOR").contains(role) || !validPasswordHash(passwordHash) ||
            ("CUSTOMER".equals(role) && (!(customerId instanceof String value) || value.isBlank() ||
                !(customerName instanceof String name) || name.isBlank())) ||
            users.containsKey(email) ||
            users.values().stream().anyMatch(user -> id.equals(user.get("id")))) {
            throw new IllegalStateException("Invalid user record in configured user file");
        }
        var user=new HashMap<String,Object>();
        user.put("email",email); user.put("id",id); user.put("role",role);
        if (customerId!=null) user.put("customerId",customerId);
        if (customerName!=null) user.put("customerName",customerName);
        user.put("password",passwordHash);
        users.put(email,user);
    }
    private static boolean validPasswordHash(String encoded) {
        Matcher matcher=ARGON2ID_HASH.matcher(encoded);
        if(!matcher.matches())return false;
        try{
            int memory=Integer.parseInt(matcher.group(1)),iterations=Integer.parseInt(matcher.group(2)),parallelism=Integer.parseInt(matcher.group(3));
            int saltBytes=Base64.getDecoder().decode(matcher.group(4)).length;
            int hashBytes=Base64.getDecoder().decode(matcher.group(5)).length;
            return memory>=8192&&memory<=262144&&iterations>=1&&iterations<=6&&parallelism>=1&&parallelism<=4&&saltBytes>=16&&hashBytes>=32;
        }catch(RuntimeException ex){return false;}
    }
    private ResponseStatusException err(HttpStatus s, String code, String msg, String hint) { return new ResponseStatusException(s, code + "|" + msg + "|" + hint); }
    private ResponseStatusException errDetails(HttpStatus s,String code,String msg,String hint,Map<String,Object> details){try{return new ResponseStatusException(s,code+"|"+msg+"|"+hint+"|"+json.writeValueAsString(details));}catch(Exception e){return err(s,code,msg,hint);}}
    private Map<String,Object> errorBody(String code,String msg,String hint,Map<String,Object> details) { return Map.of("code",code,"message",msg,"hint",hint,"details",details); }
    private String jwt(Map<String,Object> u) throws Exception {
        long now=Instant.now().getEpochSecond(); var h=Map.of("alg","HS256","typ","JWT"); var p=new HashMap<String,Object>(); p.put("sub",u.get("id"));p.put("role",u.get("role"));p.put("iss","orderflow");p.put("aud",List.of("orderflow-web"));p.put("iat",now);p.put("exp",now+900); if(u.get("customerId")!=null){p.put("customerId",u.get("customerId"));p.put("customerName",u.get("customerName"));}
        String body=b64(json.writeValueAsBytes(h))+"."+b64(json.writeValueAsBytes(p)); Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return body+"."+b64(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
    private static String b64(byte[] v){return Base64.getUrlEncoder().withoutPadding().encodeToString(v);}
    private String rateKey(String email) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8)));
    }
    private boolean audienceValid(Object audience){return audience instanceof Collection<?> values?values.contains("orderflow-web"):"orderflow-web".equals(audience);}
    private Map<String,Object> actor(HttpServletRequest req) {
        String auth=req.getHeader("Authorization"); if(auth==null||!auth.startsWith("Bearer ")) throw err(HttpStatus.UNAUTHORIZED,"UNAUTHENTICATED","Sign in is required.",CART_HINT);
        try { String[] t=auth.substring(7).split("\\."); if(t.length!=3) throw new Exception(); var head=json.readValue(Base64.getUrlDecoder().decode(t[0]),Map.class); if(!"HS256".equals(head.get("alg")))throw new Exception();Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));if(!MessageDigest.isEqual(m.doFinal((t[0]+"."+t[1]).getBytes(StandardCharsets.UTF_8)),Base64.getUrlDecoder().decode(t[2])))throw new Exception();var a=json.readValue(Base64.getUrlDecoder().decode(t[1]),new TypeReference<Map<String,Object>>(){}); if(!"orderflow".equals(a.get("iss"))||!audienceValid(a.get("aud"))||!Set.of("CUSTOMER","OPERATOR").contains(a.get("role"))||((Number)a.get("exp")).longValue()<Instant.now().getEpochSecond())throw new Exception();return a; }catch(Exception e){throw err(HttpStatus.UNAUTHORIZED,"UNAUTHENTICATED","Sign in is required.",CART_HINT);}
    }
    private void role(Map<String,Object>a,String r){if(!r.equals(a.get("role")))throw err(HttpStatus.FORBIDDEN,"FORBIDDEN","This account cannot do that.",FORBIDDEN_HINT);}
    private Map<String,Object> copy(Map<String,Object> o){return new HashMap<>(o);}
    private int quantity(Object value){if(!(value instanceof Number number))return 0;double n=number.doubleValue();return Double.isFinite(n)&&n>=1&&n<=99&&n==Math.rint(n)?(int)n:0;}
    @PostMapping("/api/auth/login") Map<String,Object> login(@RequestBody Map<String,String>b,HttpServletRequest req) throws Exception {
        String email=Optional.ofNullable(b.get("email")).orElse("").trim().toLowerCase(Locale.ROOT);
        if(!allow("login:"+req.getRemoteAddr()+":"+rateKey(email),5))throw err(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Too many attempts, so this one was paused.","Wait about a minute, then try the same action again.");
        Map<String,Object> u=users.getOrDefault(email,Map.of()); if(u.isEmpty()||!encoder.matches(Optional.ofNullable(b.get("password")).orElse(""),String.valueOf(u.get("password"))))throw err(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","Email or password is incorrect",BAD_HINT);
        return Map.of("accessToken",jwt(u),"expiresIn",900,"role",u.get("role"));
    }
    private synchronized boolean allow(String key,int max){
        long now=System.currentTimeMillis(),cut=now-60000;
        if(now-lastRateCleanup>=30000){
            rate.entrySet().removeIf(entry->{var q=entry.getValue();while(!q.isEmpty()&&q.peekFirst()<cut)q.removeFirst();return q.isEmpty();});
            lastRateCleanup=now;
        }
        if(key==null||key.isBlank()||max<1)return false;
        var queue=rate.get(key);
        if(queue!=null)while(!queue.isEmpty()&&queue.peekFirst()<cut)queue.removeFirst();
        if(queue==null&&rate.size()>=10000)return false;
        if(queue==null){queue=new java.util.ArrayDeque<>();rate.put(key,queue);}
        if(queue.size()>=max)return false;
        queue.addLast(now);
        return true;
    }
    @GetMapping("/api/catalog") Object products(HttpServletRequest r){actor(r);return catalog.stream().filter(p->Boolean.TRUE.equals(p.get("active"))).toList();}
    @PostMapping("/api/orders") synchronized ResponseEntity<?> place(@RequestBody Map<String,Object>b,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest req){var a=actor(req);role(a,"CUSTOMER");if(!allow("place:"+a.get("sub"),30))throw err(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Too many attempts, so this one was paused.","Wait about a minute, then try the same action again.");if(key==null||key.isBlank()||key.length()>128)throw err(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","That order could not be placed.","Try again from your cart.");
        Object raw=b.get("items");if(!(raw instanceof List<?> lines)||lines.isEmpty())throw err(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Your cart is empty.","Add a product before placing the order.");
        List<Map<String,Object>> parsed=new ArrayList<>(); Set<String> skus=new HashSet<>(); lines.forEach(x->{if(!(x instanceof Map<?,?> line))throw err(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Check the items in your cart.","Choose products from the catalog and try again.");String sku=line.get("sku") instanceof String value?value:"";int q=quantity(line.get("quantity"));if(sku.isBlank()||q==0||!skus.add(sku))throw err(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Check the quantities in your cart.","Each product can appear once, with a quantity from 1 to 99.");Map<String,Object> p=catalog.stream().filter(c->sku.equals(c.get("sku"))&&Boolean.TRUE.equals(c.get("active"))).findFirst().orElseThrow(()->errDetails(HttpStatus.BAD_REQUEST,"UNKNOWN_SKU","That product is not for sale.","Remove it from the cart and choose a product that is still in the catalog.",Map.of("sku",sku)));long price=((Number)p.get("unitPriceMinor")).longValue();Map<String,Object> l=new HashMap<>();l.put("sku",sku);l.put("productName",p.get("name"));l.put("quantity",q);l.put("unitPriceMinor",price);l.put("lineTotalMinor",Math.multiplyExact(price,q));parsed.add(l);});
        parsed.sort(Comparator.comparing(x->String.valueOf(x.get("sku"))));String canonical="{\"items\":["+parsed.stream().map(x->"{\"sku\":\""+x.get("sku")+"\",\"quantity\":"+x.get("quantity")+"}").reduce((x,y)->x+","+y).orElse("")+ "]}";String hash;try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new RuntimeException(e);}var replay=orders.findByIdempotency(String.valueOf(a.get("customerId")),key);if(replay.isPresent()){var order=replay.get();if(!hash.equals(order.get("requestHash")))throw err(HttpStatus.CONFLICT,"IDEMPOTENCY_PAYLOAD_MISMATCH","This order was already sent with different items.","Return to the catalog and place it again. The new order gets its own number.");return ResponseEntity.ok().header("Idempotency-Replayed","true").body(detailView(order));}
        long now=System.currentTimeMillis();String id="ord_"+Long.toString(now,36)+UUID.randomUUID().toString().replace("-","").substring(0,12);long total=parsed.stream().mapToLong(x->((Number)x.get("lineTotalMinor")).longValue()).reduce(0,Math::addExact);Map<String,Object> order=new HashMap<>();order.put("orderId",id);order.put("customerId",a.get("customerId"));order.put("customerName",a.get("customerName"));order.put("status","PENDING");order.put("items",parsed);order.put("totalMinor",total);order.put("currency","USD");order.put("version",1);order.put("idempotencyKey",key);order.put("requestHash",hash);order.put("createdAtEpochMillis",now);order.put("createdAt",ORDER_TIMESTAMP.format(Instant.ofEpochMilli(now)));order.put("updatedAt",Instant.ofEpochMilli(now).toString());order.put("processAfter",Instant.ofEpochMilli(now+delay).toString());order.put("history",new ArrayList<>(List.of(Map.of("from","","to","PENDING","at",Instant.ofEpochMilli(now).toString(),"actorRole","CUSTOMER","actorId",a.get("sub")))));if(!orders.insert(order)){var winner=orders.findByIdempotency(String.valueOf(a.get("customerId")),key).orElseThrow();if(!hash.equals(winner.get("requestHash")))throw err(HttpStatus.CONFLICT,"IDEMPOTENCY_PAYLOAD_MISMATCH","This order was already sent with different items.","Return to the catalog and place it again. The new order gets its own number.");return ResponseEntity.ok().header("Idempotency-Replayed","true").body(detailView(winner));}return ResponseEntity.status(201).body(detailView(order));
    }
    @GetMapping("/api/orders") Object list(@RequestParam(required=false)String status,@RequestParam(required=false)String customerId,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)String cursor,HttpServletRequest req){
        var a=actor(req);
        int n=Math.max(1,Math.min(100,limit));
        Long cursorTimeMillis=null;
        String cursorId=null;
        if(cursor!=null){
            try{
                String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",2);
                if(parts.length!=2||!parts[1].matches("^ord_[a-z0-9]{1,44}$"))throw new IllegalArgumentException();
                Instant parsedTime=Instant.parse(parts[0]);
                if(parsedTime.getNano()%1_000_000!=0)throw new IllegalArgumentException();
                cursorTimeMillis=parsedTime.toEpochMilli(); cursorId=parts[1];
            }catch(Exception ex){throw err(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","That order page could not be loaded.","Refresh the order list and try again.");}
        }
        String owner="CUSTOMER".equals(a.get("role"))?String.valueOf(a.get("customerId")):customerId;
        List<Map<String,Object>> page=orders.findPage(owner,status,cursorTimeMillis,cursorId,n+1);
        boolean more=page.size()>n;
        if(more)page=page.subList(0,n);
        var result=new HashMap<String,Object>();
        result.put("items",page.stream().map(this::listView).toList());
        if(more&&!page.isEmpty()){
            var last=page.get(page.size()-1);
            result.put("nextCursor",Base64.getUrlEncoder().withoutPadding().encodeToString((last.get("createdAt")+"|"+last.get("orderId")).getBytes(StandardCharsets.UTF_8)));
        }
        return result;
    }
    private Map<String,Object> listView(Map<String,Object> o){var c=copy(o);c.remove("history");c.remove("requestHash");c.remove("idempotencyKey");c.remove("createdAtEpochMillis");return c;}
    private Map<String,Object> detailView(Map<String,Object> o){var c=copy(o);c.remove("requestHash");c.remove("idempotencyKey");c.remove("createdAtEpochMillis");return c;}
    private Map<String,Object> ownedOrder(String id,Map<String,Object> actor){var o=orders.findById(id).orElse(null);if(o==null||("CUSTOMER".equals(actor.get("role"))&&!Objects.equals(o.get("customerId"),actor.get("customerId"))))throw err(HttpStatus.NOT_FOUND,"ORDER_NOT_FOUND","We could not find that order.","Go back to your orders. An order from another account stays hidden.");return o;}
    @GetMapping("/api/orders/{id}") Object get(@PathVariable String id,HttpServletRequest req){return detailView(ownedOrder(id,actor(req)));}
    @PostMapping("/api/orders/{id}/cancel") synchronized Object cancel(@PathVariable String id,@RequestBody(required=false)Map<String,String>b,HttpServletRequest req){var a=actor(req);role(a,"CUSTOMER");var o=ownedOrder(id,a);try{OrderStateMachine.assertCancellable(String.valueOf(o.get("status")));}catch(IllegalArgumentException ex){throw err(HttpStatus.CONFLICT,"CANCEL_NOT_ALLOWED","This order can be cancelled only while it is pending.","Leave it to finish, or place a new order from the catalog if you still need the items.");}String reason=b==null?"":b.getOrDefault("reason","");if(reason.length()>200)throw err(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","That reason is too long.","Keep it under 200 characters.");return detailView(transition(o,"CANCELLED",a,"CUSTOMER",reason));}
    @PatchMapping("/api/orders/{id}/status") synchronized Object status(@PathVariable String id,@RequestBody Map<String,String>b,HttpServletRequest req){var a=actor(req);role(a,"OPERATOR");var o=ownedOrder(id,a);String next=b.get("status");if(Objects.equals(next,o.get("status")))return detailView(o);String expected;try{expected=OrderStateMachine.forward(String.valueOf(o.get("status")));}catch(IllegalArgumentException ex){expected="";}if(!Objects.equals(expected,next))throw errDetails(HttpStatus.CONFLICT,"INVALID_STATUS_TRANSITION","That status change is not allowed.","Refresh the order. It can only move one step forward: Processing, then Shipped, then Delivered.",Map.of("currentStatus",o.get("status")));return detailView(transition(o,next,a,"OPERATOR",null));}
    private synchronized Map<String,Object> transition(Map<String,Object> old,String next,Map<String,Object>a,String role,String reason){var o=copy(old);String from=String.valueOf(o.get("status"));if("CANCELLED".equals(next))OrderStateMachine.assertCancellable(from);else if(!OrderStateMachine.forward(from).equals(next))throw new IllegalArgumentException("INVALID_STATUS_TRANSITION");long now=System.currentTimeMillis();o.put("status",next);o.put("version",((Number)o.get("version")).intValue()+1);o.put("updatedAt",Instant.ofEpochMilli(now).toString());o.remove("processAfter");if(reason!=null)o.put("cancelReason",reason);var h=new ArrayList<>((List<Map<String,Object>>)o.get("history"));h.add(Map.of("from",from,"to",next,"at",Instant.ofEpochMilli(now).toString(),"actorRole",role,"actorId",a.getOrDefault("sub","worker")));o.put("history",h);if(orders.compareAndSet(String.valueOf(o.get("orderId")),from,((Number)old.get("version")).intValue(),o).isEmpty())throw errDetails(HttpStatus.CONFLICT,"INVALID_STATUS_TRANSITION","That status change is not allowed.","Refresh the order and try again.",Map.of("currentStatus",from));return o;}
    @GetMapping("/internal/orders/due") Object due(@RequestParam(defaultValue="50")int limit,@RequestHeader(value="X-Internal-Token",required=false)String token){internal(token);int n=Math.max(1,Math.min(100,limit));return Map.of("orderIds",orders.findDue(System.currentTimeMillis(),n).stream().map(o->o.get("orderId")).toList());}
    @PostMapping("/internal/orders/{id}/promote") synchronized Object promote(@PathVariable String id,@RequestHeader(value="X-Internal-Token",required=false)String token){internal(token);var o=orders.findById(id).orElse(null);if(o==null)throw err(HttpStatus.NOT_FOUND,"ORDER_NOT_FOUND","We could not find that order.","Go back to your orders.");if(!"PENDING".equals(o.get("status")))throw err(HttpStatus.CONFLICT,"INVALID_STATUS_TRANSITION","That status change is not allowed.","Refresh the order.");if(!o.containsKey("processAfter")||Instant.parse(String.valueOf(o.get("processAfter"))).toEpochMilli()>System.currentTimeMillis())throw err(HttpStatus.CONFLICT,"NOT_DUE","This order is not ready to process yet.","Wait until the scheduled time, then the desk will move it to processing on its own.");return detailView(transition(o,"PROCESSING",Map.of("sub","worker"),"WORKER",null));}
    private void internal(String provided){try{if(internalToken==null||internalToken.isEmpty()||provided==null)throw new Exception();byte[] a=MessageDigest.getInstance("SHA-256").digest(internalToken.getBytes(StandardCharsets.UTF_8)),b=MessageDigest.getInstance("SHA-256").digest(provided.getBytes(StandardCharsets.UTF_8));if(!MessageDigest.isEqual(a,b))throw new Exception();}catch(Exception e){throw err(HttpStatus.UNAUTHORIZED,"UNAUTHENTICATED","Sign in is required.",CART_HINT);}}
    @GetMapping("/actuator/health/readiness") Object health(){return Map.of("status","UP");}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> handle(ResponseStatusException e){String[] p=Optional.ofNullable(e.getReason()).orElse("INTERNAL|Something went wrong on our side.|Wait a moment and try again.").split("\\|",4);Map<String,Object> details=Map.of();if(p.length>3)try{details=json.readValue(p[3],new TypeReference<>(){});}catch(Exception ignored){}return ResponseEntity.status(e.getStatusCode()).body(errorBody(p[0],p[1],p.length>2?p[2]:"Try again.",details));}
    @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<?> unreadable(HttpMessageNotReadableException e){return ResponseEntity.badRequest().body(errorBody("VALIDATION_ERROR","That request could not be read.","Check the information and try again.",Map.of()));}
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class) ResponseEntity<?> wrongMethod(HttpRequestMethodNotSupportedException e){return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(errorBody("METHOD_NOT_ALLOWED","That action is not available here.","Choose an action available on this page.",Map.of()));}
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class) ResponseEntity<?> wrongType(HttpMediaTypeNotSupportedException e){return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(errorBody("UNSUPPORTED_MEDIA_TYPE","That request format is not supported.","Send the information as JSON.",Map.of()));}
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e){System.err.println("request.failed code=INTERNAL");return ResponseEntity.internalServerError().body(errorBody("INTERNAL","Something went wrong on our side.","Wait a moment and try again. Orders you already placed are unchanged, and your cart is still in this browser.",Map.of()));}
    @Scheduled(fixedDelayString="${orderflow.promote-interval-ms:15000}") void scheduled(){if(!embedded)return;for(Object id:(List<?>)((Map<?,?>)due(50,internalToken)).get("orderIds")){try{promote(String.valueOf(id),internalToken);}catch(Exception ignored){}}}
}

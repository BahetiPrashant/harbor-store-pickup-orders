package io.orderflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProductionConfigurationTest {
    @TempDir Path temp;
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void productionLoadsProvisionedUsersWithArgon2idHashes() throws Exception {
        Path usersFile=temp.resolve("users.json");
        String password="a-strong-customer-password";
        String passwordHash=Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(password);
        mapper.writeValue(usersFile.toFile(),List.of(Map.of(
            "email","Customer@Example.com","id","user-customer","role","CUSTOMER",
            "customerId","customer-001","customerName","Example Customer","passwordHash",passwordHash)));

        Api api=productionApi();
        api.usersFile=usersFile.toString();
        api.init();

        HttpServletRequest request=new MockHttpServletRequest();
        Map<String,Object> session=api.login(Map.of("email","customer@example.com","password",password),request);
        assertEquals("CUSTOMER",session.get("role"));
        assertNotNull(session.get("accessToken"));
    }

    @Test void productionRefusesToStartWithoutUsers() {
        Api api=productionApi();
        IllegalStateException error=assertThrows(IllegalStateException.class,api::init);
        assertEquals("Unsafe or incomplete production configuration",error.getMessage());
    }

    @Test void productionRefusesKnownPlaceholderSecrets() throws Exception {
        Path usersFile=temp.resolve("users.json");
        String passwordHash=Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("a-strong-password");
        mapper.writeValue(usersFile.toFile(),List.of(Map.of(
            "email","operator@example.com","id","user-operator","role","OPERATOR","passwordHash",passwordHash)));
        Api api=productionApi();
        api.usersFile=usersFile.toString();
        api.jwtSecret="replace-this-with-a-long-production-jwt-secret";
        IllegalStateException error=assertThrows(IllegalStateException.class,api::init);
        assertEquals("Unsafe or incomplete production configuration",error.getMessage());
    }

    @Test void productionRefusesLowVariationSecrets() throws Exception {
        Path usersFile=temp.resolve("users-low-variation.json");
        String passwordHash=Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("a-strong-password");
        mapper.writeValue(usersFile.toFile(),List.of(Map.of(
            "email","operator@example.com","id","user-operator","role","OPERATOR","passwordHash",passwordHash)));
        Api api=productionApi();
        api.usersFile=usersFile.toString();
        api.jwtSecret="A".repeat(48);
        IllegalStateException error=assertThrows(IllegalStateException.class,api::init);
        assertEquals("Unsafe or incomplete production configuration",error.getMessage());
    }

    @Test void productionRefusesMissingDatabasePassword() throws Exception {
        Path usersFile=temp.resolve("users-db-password.json");
        String passwordHash=Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("a-strong-password");
        mapper.writeValue(usersFile.toFile(),List.of(Map.of(
            "email","operator@example.com","id","user-operator","role","OPERATOR","passwordHash",passwordHash)));
        Api api=productionApi();
        api.usersFile=usersFile.toString();
        api.databasePassword="";
        IllegalStateException error=assertThrows(IllegalStateException.class,api::init);
        assertEquals("Unsafe or incomplete production configuration",error.getMessage());
    }

    @Test void productionRejectsMalformedArgon2idHashesAtStartup() throws Exception {
        Path usersFile=temp.resolve("users-invalid-hash.json");
        mapper.writeValue(usersFile.toFile(),List.of(Map.of(
            "email","operator@example.com","id","user-operator","role","OPERATOR","passwordHash","$argon2id$not-a-valid-hash")));
        Api api=productionApi();
        api.usersFile=usersFile.toString();
        IllegalStateException error=assertThrows(IllegalStateException.class,api::init);
        assertEquals("Invalid user record in configured user file",error.getMessage());
    }

    private Api productionApi() {
        Api api=new Api();
        api.seed=false;
        api.activeProfiles="prod";
        api.jwtSecret=randomSecret();
        api.internalToken=randomSecret();
        api.databasePassword=randomSecret();
        return api;
    }

    private static String randomSecret() {
        byte[] bytes=new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

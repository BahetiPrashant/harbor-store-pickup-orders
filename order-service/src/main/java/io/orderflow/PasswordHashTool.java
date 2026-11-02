package io.orderflow;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import java.io.Console;
import java.util.Arrays;

/** Small local helper for creating password hashes for the production users file. */
public final class PasswordHashTool {
    private PasswordHashTool() {}

    public static void main(String[] args) {
        Console console=System.console();
        if (console==null || args.length!=0) {
            System.err.println("Run this tool in an interactive terminal without arguments.");
            System.exit(2);
        }
        char[] password=console.readPassword("Password to hash: ");
        if (password==null || password.length<12) {
            if (password!=null) Arrays.fill(password,'\0');
            System.err.println("Use a password with at least 12 characters.");
            System.exit(2);
        }
        try {
            System.out.println(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(new String(password)));
        } finally {
            Arrays.fill(password,'\0');
        }
    }
}

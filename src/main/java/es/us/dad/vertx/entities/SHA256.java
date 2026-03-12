package es.us.dad.vertx.entities;

import java.security.MessageDigest;

public class SHA256 {
    public static String applySha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Aplicar hash al input
            byte[] hash = digest.digest(input.getBytes("UTF-8"));

            // Convertir bytes a Hexadecimal (String)
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

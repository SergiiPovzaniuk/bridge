package com.openaiapi.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PayloadCryptoTest {

    @Test
    void roundTrip() {
        PayloadCrypto crypto = new PayloadCrypto("dev-shared-transport-key");
        String plain = "{\"model\":\"auto\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        String sealed = crypto.seal(plain);
        assertTrue(sealed.startsWith(PayloadCrypto.PREFIX));
        assertNotEquals(plain, sealed);
        assertEquals(plain, crypto.unseal(sealed));
    }

    @Test
    void wrongKeyFails() {
        PayloadCrypto a = new PayloadCrypto("key-a");
        PayloadCrypto b = new PayloadCrypto("key-b");
        String sealed = a.seal("secret");
        assertThrows(IllegalArgumentException.class, () -> b.unseal(sealed));
    }

    @Test
    void missingPrefixFails() {
        PayloadCrypto crypto = new PayloadCrypto("dev-shared-transport-key");
        assertThrows(IllegalArgumentException.class, () -> crypto.unseal("not-sealed"));
    }
}

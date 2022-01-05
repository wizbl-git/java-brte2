package com.wizbl.common.crypto;

import com.wizbl.common.crypto.ECKey.ECDSASignature;
import com.wizbl.core.Wallet;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.spongycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.SignatureException;
import java.util.Arrays;

import static org.junit.Assert.*;

//TEST PASSED
@Slf4j
public class ECKeyTest {

    private final String privString = "baccbacad7c1e7a40142ec477decf1d5e57f5e0d3211ac63c5e046220ec19c0f";
    private final BigInteger privateKey = new BigInteger(privString, 16);

    private final String pubString = "04350ef59a2190065e134d17a51bd5f6b873997b7f9e53d4510877e0db1f8b0f14a3e758aa4104c9ac8b2ae14da944b6d67cb20959157df80a73ba3c036009734f";
    private final String compressedPubString = "03350ef59a2190065e134d17a51bd5f6b873997b7f9e53d4510877e0db1f8b0f14";
    private final byte[] pubKey = Hex.decode(pubString);
    private final byte[] compressedPubKey = Hex.decode(compressedPubString);
    private final String address = "f9cf91635e8a0c9e4a475336486cfa59b267b9ed";

    @Test
    public void testHashCode() {
        assertEquals(1039672440, ECKey.fromPrivate(privateKey).hashCode());
    }

    @Test
    public void testECKey() {
        ECKey key = new ECKey();
        assertTrue(key.isPubKeyCanonical());
        assertNotNull(key.getPubKey());
        assertNotNull(key.getPrivKeyBytes());
        logger.info("Generated privKey = {}", Hex.toHexString(key.getPrivKeyBytes()));
        logger.info("Generated pubKey = {}", Hex.toHexString(key.getPubKey()));
        logger.info(Hex.toHexString(key.getPrivKeyBytes()) + " :Generated privkey");
        logger.info(Hex.toHexString(key.getPubKey()) + " :Generated pubkey");
    }

    @Test
    public void testFromPrivateKey() {
        ECKey key = ECKey.fromPrivate(privateKey);
        assertTrue(key.isPubKeyCanonical());
        assertTrue(key.hasPrivKey());
        assertArrayEquals(pubKey, key.getPubKey());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPrivatePublicKeyBytesNoArg() {
        new ECKey((BigInteger) null, null);
        fail("Expecting an IllegalArgumentException for using only null-parameters");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidPrivateKey() throws Exception {
        new ECKey(
                Security.getProvider("SunEC"),
                KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate(),
                ECKey.fromPublicOnly(pubKey).getPubKeyPoint());
        fail("Expecting an IllegalArgumentException for using an non EC private key");
    }

    @Test
    public void testIsPubKeyOnly() {
        ECKey key = ECKey.fromPublicOnly(pubKey);
        assertTrue(key.isPubKeyCanonical());
        assertTrue(key.isPubKeyOnly());
        assertArrayEquals(key.getPubKey(), pubKey);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSignIncorrectInputSize() {
        ECKey key = new ECKey();
        String message = "The quick brown fox jumps over the lazy dog.";
        ECDSASignature sig = key.doSign(message.getBytes());
        fail("Expecting an IllegalArgumentException for a non 32-byte input");
    }

    @Test(expected = SignatureException.class)
    public void testBadBase64Sig() throws SignatureException {
        byte[] messageHash = new byte[32];
        ECKey.signatureToKey(messageHash, "This is not valid Base64!");
        fail("Expecting a SignatureException for invalid Base64");
    }

    @Test(expected = SignatureException.class)
    public void testInvalidSignatureLength() throws SignatureException {
        byte[] messageHash = new byte[32];
        ECKey.signatureToKey(messageHash, "abcdefg");
        fail("Expecting a SignatureException for invalid signature length");
    }

    @Test
    public void testPublicKeyFromPrivate() {
        byte[] pubFromPriv = ECKey.publicKeyFromPrivate(privateKey, false);
        assertArrayEquals(pubKey, pubFromPriv);
    }

    @Test
    public void testPublicKeyFromPrivateCompressed() {
        byte[] pubFromPrivCompressed = ECKey.publicKeyFromPrivate(privateKey, true);
        assertArrayEquals(compressedPubKey, pubFromPrivCompressed);
    }

    @Test
    public void testGetAddress() {
        ECKey key = ECKey.fromPublicOnly(pubKey);
        // Addresses are prefixed with a constant.
        byte[] prefixedAddress = key.getAddress();
        byte[] unprefixedAddress = Arrays.copyOfRange(key.getAddress(), 4, prefixedAddress.length);
        assertArrayEquals(Hex.decode(address), unprefixedAddress);
        assertArrayEquals(Wallet.getAddressPreFixByte(), Arrays.copyOfRange(prefixedAddress, 0, 4));
    }

    @Test
    public void testGetAddressFromPrivateKey() {
        ECKey key = ECKey.fromPrivate(privateKey);
        // Addresses are prefixed with a constant.
        byte[] prefixedAddress = key.getAddress();
        byte[] unprefixedAddress = Arrays.copyOfRange(key.getAddress(), 4, prefixedAddress.length);
        assertArrayEquals(Hex.decode(address), unprefixedAddress);
        assertArrayEquals(Wallet.getAddressPreFixByte(), Arrays.copyOfRange(prefixedAddress, 0, 4));
    }

    @Test
    public void testToString() {
        ECKey key = ECKey.fromPrivate(BigInteger.TEN); // An example private key.
        assertEquals("pub:04a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7893aba425419bc27a3b6c7e693a24c696f794c2ed877a1593cbee53b037368d7", key.toString());
    }

    @Test
    public void testIsPubKeyCanonicalCorect() {
        // Test correct prefix 4, right length 65
        byte[] canonicalPubkey1 = new byte[65];
        canonicalPubkey1[0] = 0x04;
        assertTrue(ECKey.isPubKeyCanonical(canonicalPubkey1));
        // Test correct prefix 2, right length 33
        byte[] canonicalPubkey2 = new byte[33];
        canonicalPubkey2[0] = 0x02;
        assertTrue(ECKey.isPubKeyCanonical(canonicalPubkey2));
        // Test correct prefix 3, right length 33
        byte[] canonicalPubkey3 = new byte[33];
        canonicalPubkey3[0] = 0x03;
        assertTrue(ECKey.isPubKeyCanonical(canonicalPubkey3));
    }

    @Test
    public void testIsPubKeyCanonicalWrongLength() {
        // Test correct prefix 4, but wrong length !65
        byte[] nonCanonicalPubkey1 = new byte[64];
        nonCanonicalPubkey1[0] = 0x04;
        assertFalse(ECKey.isPubKeyCanonical(nonCanonicalPubkey1));
        // Test correct prefix 2, but wrong length !33
        byte[] nonCanonicalPubkey2 = new byte[32];
        nonCanonicalPubkey2[0] = 0x02;
        assertFalse(ECKey.isPubKeyCanonical(nonCanonicalPubkey2));
        // Test correct prefix 3, but wrong length !33
        byte[] nonCanonicalPubkey3 = new byte[32];
        nonCanonicalPubkey3[0] = 0x03;
        assertFalse(ECKey.isPubKeyCanonical(nonCanonicalPubkey3));
    }

    @Test
    public void testIsPubKeyCanonicalWrongPrefix() {
        // Test wrong prefix 4, right length 65
        byte[] nonCanonicalPubkey4 = new byte[65];
        assertFalse(ECKey.isPubKeyCanonical(nonCanonicalPubkey4));
        // Test wrong prefix 2, right length 33
        byte[] nonCanonicalPubkey5 = new byte[33];
        assertFalse(ECKey.isPubKeyCanonical(nonCanonicalPubkey5));
        // Test wrong prefix 3, right length 33
        byte[] nonCanonicalPubkey6 = new byte[33];
        assertFalse(ECKey.isPubKeyCanonical(nonCanonicalPubkey6));
    }

    @Test
    public void testGetPrivKeyBytes() {
        ECKey key = new ECKey();
        assertNotNull(key.getPrivKeyBytes());
        assertEquals(32, key.getPrivKeyBytes().length);
    }

    @Test
    public void testEqualsObject() {
        ECKey key0 = new ECKey();
        ECKey key1 = ECKey.fromPrivate(privateKey);
        ECKey key2 = ECKey.fromPrivate(privateKey);

        assertFalse(key0.equals(key1));
        assertTrue(key1.equals(key1));
        assertTrue(key1.equals(key2));
    }

    @Test
    public void decryptAECSIC() {
        ECKey key = ECKey.fromPrivate(Hex.decode("abb51256c1324a1350598653f46aa3ad693ac3cf5d05f36eba3f495a1f51590f"));
        byte[] payload = key.decryptAES(Hex.decode("84a727bc81fa4b13947dc9728b88fd08"));
        System.out.println(Hex.toHexString(payload));
    }

    @Test
    public void testNodeId() {
        ECKey key = ECKey.fromPublicOnly(pubKey);

        assertEquals(key, ECKey.fromNodeId(key.getNodeId()));
    }
}
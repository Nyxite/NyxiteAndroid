// Spike P0.1-CORE-1: does BouncyCastle for Java, the candidate PQC library of the Android client, reproduce the
// shared conformance vectors byte for byte? Runs on a desktop JVM; the same jar is what an Android build would bundle.
// usage: java -cp lib/bcprov-jdk18on-<version>.jar Check.java [vector-directory]
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.crypto.digests.Blake3Digest;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.hpke.HPKE;
import org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.MLDSAParameters;
import org.bouncycastle.crypto.params.MLDSAPrivateKeyParameters;
import org.bouncycastle.crypto.params.MLDSAPublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.crypto.signers.MLDSASigner;
import org.bouncycastle.pqc.crypto.xwing.XWingKEMExtractor;
import org.bouncycastle.pqc.crypto.xwing.XWingKEMGenerator;
import org.bouncycastle.pqc.crypto.xwing.XWingPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.xwing.XWingPublicKeyParameters;

public class Check {
    static final HexFormat HEX = HexFormat.of();
    static int failed;

    static void check(String name, Object actual, Object expected) {
        boolean ok = actual.equals(expected);
        if (!ok) failed++;
        System.out.println((ok ? "pass  " : "FAIL  ") + name);
    }

    /** The flat objects of a vector file: string and integer members only, which is all the vectors use. */
    static List<Map<String, String>> load(Path dir, String name) throws Exception {
        String json = Files.readString(dir.resolve(name + ".json"));
        List<Map<String, String>> objects = new ArrayList<>();
        Matcher object = Pattern.compile("\\{[^\\{\\}]*\\}").matcher(json);
        while (object.find()) {
            Map<String, String> members = new HashMap<>();
            Matcher member = Pattern.compile("\"(\\w+)\"\\s*:\\s*(?:\"([^\"]*)\"|(-?\\d+))").matcher(object.group());
            while (member.find()) members.put(member.group(1), member.group(2) != null ? member.group(2) : member.group(3));
            objects.add(members);
        }
        return objects;
    }

    static byte[] hex(String s) { return HEX.parseHex(s); }
    static String hex(byte[] b) { return HEX.formatHex(b); }
    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
    static byte[] slice(byte[] a, int from, int to) { return java.util.Arrays.copyOfRange(a, from, to); }

    /** Hands out fixed bytes in order, to make an encapsulation reproducible. */
    static final class FixedRandom extends SecureRandom {
        private final byte[] bytes;
        private int position;
        FixedRandom(byte[] bytes) { this.bytes = bytes; }
        @Override public void nextBytes(byte[] out) {
            System.arraycopy(bytes, position, out, 0, out.length);
            position += out.length;
        }
    }

    static boolean verifyHybrid(byte[] publicKey, byte[] message, byte[] signature) {
        Ed25519Signer ed = new Ed25519Signer();
        ed.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
        ed.update(message, 0, message.length);
        boolean edOk = ed.verifySignature(slice(signature, 0, 64));
        MLDSASigner ml = new MLDSASigner();
        ml.init(false, new MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_65, slice(publicKey, 32, publicKey.length)));
        ml.update(message, 0, message.length);
        boolean mlOk = ml.verifySignature(slice(signature, 64, signature.length));
        return edOk & mlOk;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "../../../NyxiteServer/tests/Nyxite.UnitTests/Resources/vectors");

        // X-Wing KEM against the draft's own vectors.
        int i = 0;
        for (Map<String, String> v : load(dir, "xwing-kem")) {
            XWingPrivateKeyParameters sk = new XWingPrivateKeyParameters(hex(v.get("seed")));
            XWingPublicKeyParameters pk = new XWingPublicKeyParameters(hex(v.get("pk")));
            try {
                SecretWithEncapsulation e = new XWingKEMGenerator(new FixedRandom(hex(v.get("eseed")))).generateEncapsulated(pk);
                check("xwing-kem[" + i + "] encapsulate: ciphertext", hex(e.getEncapsulation()), v.get("ct"));
                check("xwing-kem[" + i + "] encapsulate: shared secret", hex(e.getSecret()), v.get("ss"));
            } catch (RuntimeException e) {
                check("xwing-kem[" + i + "] encapsulate with a fixed seed (" + e + ")", false, true);
            }
            check("xwing-kem[" + i + "] decapsulate", hex(new XWingKEMExtractor(sk).extractSecret(hex(v.get("ct")))), v.get("ss"));
            i++;
        }

        // HPKE base mode, X-Wing / HKDF-SHA256 / AES-256-GCM, single shot: wrapped = enc ‖ ciphertext ‖ tag.
        {
            Map<String, String> v = load(dir, "hybrid-hpke").get(0);
            check("hybrid-hpke suite ids", HPKE.kem_X_WING + "," + HPKE.kdf_HKDF_SHA256 + "," + HPKE.aead_AES_GCM256,
                v.get("kemId") + "," + v.get("kdfId") + "," + v.get("aeadId"));
            HPKE hpke = new HPKE(HPKE.mode_base, HPKE.kem_X_WING, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256);
            byte[] info = hex(v.get("info")), aad = hex(v.get("aad")), wrapped = hex(v.get("wrapped"));
            AsymmetricCipherKeyPair recipient = hpke.deserializePrivateKey(hex(v.get("recipientSeed")), hex(v.get("recipientPublicKey")));
            check("hybrid-hpke public key of the seed", hex(hpke.serializePublicKey(recipient.getPublic())), v.get("recipientPublicKey"));
            byte[] opened = hpke.setupBaseR(slice(wrapped, 0, 1120), recipient, info).open(aad, slice(wrapped, 1120, wrapped.length));
            check("hybrid-hpke open", hex(opened), v.get("plaintext"));

            // A fresh seal by this library, opened again here. Its output is printed so another client can open it too.
            HPKEContextWithEncapsulation sender = hpke.setupBaseS(hpke.deserializePublicKey(hex(v.get("recipientPublicKey"))), info);
            byte[] sealed = concat(sender.getEncapsulation(), sender.seal(aad, hex(v.get("plaintext"))));
            byte[] reopened = hpke.setupBaseR(slice(sealed, 0, 1120), recipient, info).open(aad, slice(sealed, 1120, sealed.length));
            check("hybrid-hpke seal then open", hex(reopened), v.get("plaintext"));
            System.out.println("sealed-by-bouncycastle " + hex(sealed));
        }

        // Hybrid signature: Ed25519 ‖ ML-DSA-65, both over the same message, ML-DSA pure and deterministic.
        {
            Map<String, String> v = load(dir, "hybrid-signature").get(0);
            byte[] message = hex(v.get("message"));
            Ed25519PrivateKeyParameters edKey = new Ed25519PrivateKeyParameters(hex(v.get("ed25519Seed")), 0);
            MLDSAPrivateKeyParameters mlKey = new MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_65, hex(v.get("mlDsa65Seed")));
            check("hybrid-signature public key",
                hex(concat(edKey.generatePublicKey().getEncoded(), mlKey.getPublicKeyParameters().getEncoded())), v.get("publicKey"));
            Ed25519Signer ed = new Ed25519Signer();
            ed.init(true, edKey);
            ed.update(message, 0, message.length);
            MLDSASigner ml = new MLDSASigner();
            ml.init(true, mlKey);
            ml.update(message, 0, message.length);
            check("hybrid-signature sign", hex(concat(ed.generateSignature(), ml.generateSignature())), v.get("signature"));
            check("hybrid-signature verify", verifyHybrid(hex(v.get("publicKey")), message, hex(v.get("signature"))), true);
            byte[] tampered = hex(v.get("signature"));
            tampered[100] ^= 1;
            check("hybrid-signature rejects a tampered ML-DSA half", verifyHybrid(hex(v.get("publicKey")), message, tampered), false);

            Map<String, String> d = load(dir, "directory-entry").get(0);
            check("directory-entry self signature", verifyHybrid(hex(d.get("sigPub")), hex(d.get("signedMessage")), hex(d.get("selfSig"))), true);
            Map<String, String> a = load(dir, "device-activation").get(0);
            check("device-activation signature", verifyHybrid(hex(a.get("signerPublicKey")), hex(a.get("signedMessage")), hex(a.get("signature"))), true);
        }

        // NYXC frame: AES-256-GCM of the platform, frame = header ‖ nonce ‖ ciphertext ‖ tag.
        {
            Map<String, String> v = load(dir, "nyxc-frame").get(0);
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hex(v.get("key")), "AES"), new GCMParameterSpec(128, hex(v.get("nonce"))));
            gcm.updateAAD(hex(v.get("aad")));
            String sealed = hex(gcm.doFinal(hex(v.get("plaintext"))));
            String frame = v.get("frame");
            check("nyxc-frame ciphertext and tag", sealed, frame.substring(frame.length() - sealed.length()));
        }

        i = 0;
        for (Map<String, String> v : load(dir, "blake3")) {
            byte[] input = hex(v.get("input")), out = new byte[32];
            Blake3Digest blake3 = new Blake3Digest(256);
            blake3.update(input, 0, input.length);
            blake3.doFinal(out, 0);
            check("blake3[" + i++ + "]", hex(out), v.get("hash"));
        }

        // Argon2id with the pinned parameters, and how long one derivation takes on this JVM.
        {
            Map<String, String> v = load(dir, "argon2id").get(0);
            Argon2BytesGenerator argon2 = new Argon2BytesGenerator();
            argon2.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(hex(v.get("salt"))).withMemoryAsKB(Integer.parseInt(v.get("memoryKiB")))
                .withIterations(Integer.parseInt(v.get("iterations"))).withParallelism(Integer.parseInt(v.get("parallelism"))).build());
            byte[] out = new byte[v.get("output").length() / 2];
            long started = System.nanoTime();
            argon2.generateBytes(hex(v.get("password")), out);
            long ms = (System.nanoTime() - started) / 1_000_000;
            check("argon2id m=" + v.get("memoryKiB") + " KiB t=" + v.get("iterations") + " p=" + v.get("parallelism")
                + " (" + ms + " ms, pure Java on " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version") + ")", hex(out), v.get("output"));
        }

        System.out.println(failed == 0 ? "\nall vectors reproduced" : "\n" + failed + " check(s) failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}

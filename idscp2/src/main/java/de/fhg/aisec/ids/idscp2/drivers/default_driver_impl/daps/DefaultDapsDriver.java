package de.fhg.aisec.ids.idscp2.drivers.default_driver_impl.daps;

import de.fhg.aisec.ids.idscp2.drivers.default_driver_impl.keystores.PreConfiguration;
import de.fhg.aisec.ids.idscp2.drivers.interfaces.DapsDriver;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.io.IOException;
import java.security.Key;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jose4j.http.Get;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwa.AlgorithmConstraints.ConstraintType;
import org.jose4j.jwk.HttpsJwks;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.keys.resolvers.HttpsJwksVerificationKeyResolver;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * Default DAPS Driver for requesting valid dynamicAttributeToken and verifying DAT
 *
 * @author Leon Beckmann (leon.beckmann@aisec.fraunhofer.de)
 */
public class DefaultDapsDriver implements DapsDriver {
    private static final  Logger LOG = LoggerFactory.getLogger(DefaultDapsDriver.class);

    private SSLSocketFactory sslSocketFactory;
    private X509ExtendedTrustManager trustManager;
    private Key privateKey;
    private String connectorUUID;
    private String dapsUrl;
    private String targetAudience = "IDS_Connector";

    public DefaultDapsDriver(DefaultDapsDriverConfig config) {

        this.connectorUUID = config.getConnectorUUID();
        this.dapsUrl = config.getDapsUrl();

        //create ssl context
        privateKey = PreConfiguration.getKey(
            config.getKeyStorePath(),
            config.getKeyStorePassword(),
            config.getKeyAlias()
        );

        TrustManager[] trustManagers = PreConfiguration.getX509ExtTrustManager(
            config.getTrustStorePath(),
            config.getTrustStorePassword()
        );

        this.trustManager = (X509ExtendedTrustManager) trustManagers[0];

        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers, null);
            sslSocketFactory = sslContext.getSocketFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            LOG.error("Cannot init DefaultDapsDriver: {}", e.toString());
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] getToken() {
        String invalidToken = "INVALID_TOKEN";
        String token;

        LOG.info("Retrieving Dynamic Attribute Token...");

        //create signed JWT
        String jwt =
            Jwts.builder()
            .setIssuer(connectorUUID)
            .setSubject(connectorUUID)
            .setExpiration(Date.from(Instant.now().plusSeconds(86400)))
            .setIssuedAt(Date.from(Instant.now()))
            .setNotBefore(Date.from(Instant.now()))
            .setAudience(targetAudience)
            .signWith(privateKey, SignatureAlgorithm.RS256).compact();

        //build http client and request for DAPS
        RequestBody formBody =
            new FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add(
                    "client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
                .add("client_assertion", jwt)
                .add("scope", "ids_connector")
                .build();

        OkHttpClient client =
            new OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustManager)
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        Request request =
            new Request.Builder()
            .url(dapsUrl.concat("/token"))
            .post(formBody)
            .build();

        try {
            //get http response from DAPS
            LOG.debug("Acquire DAT from {}", dapsUrl);
            Response response = client.newCall(request).execute();

            //check for valid response
            if (!response.isSuccessful()) {
                LOG.error("Get unsuccessful http response: {}", response.toString());
                return invalidToken.getBytes();
            }

            if (response.body() == null) {
                LOG.error("Get empty DAPS response");
                return invalidToken.getBytes();
            }

            JSONObject json = new JSONObject(response.body().string());
            if (json.has("access_token")) {
                token = json.getString("access_token");
                LOG.info("Get access_token from DAPS: {}", token);
            } else if (json.has("error")) {
                LOG.error("Get DAPS error response: {}", json.getString("error"));
                return invalidToken.getBytes();
            } else {
                LOG.error("Get unknown DAPS response format: {}", json.toString());
                return invalidToken.getBytes();
            }

            //verify token once without security attr validation before providing it
            if (verifyToken(token.getBytes(), null) > 0) {
                LOG.info("DAT is valid");
                return token.getBytes();
            } else {
                LOG.error("DAT validation failed");
                return invalidToken.getBytes();
            }
        } catch (IOException e) {
            LOG.error("Cannot acquire DAT from DAPS: ", e);
            return invalidToken.getBytes();
        }
    }

    @Override
    public int verifyToken(byte[] dat, Object securityRequirements) {

        LOG.info("Verify dynamic attribute token ...");

        // Get JsonWebKey JWK from JsonWebKeyStore JWKS using DAPS JWKS endpoint
        HttpsJwks httpsJwks = new HttpsJwks(dapsUrl.concat("/.well-known/jwks.json"));
        Get getInstance = new Get();
        getInstance.setSslSocketFactory(sslSocketFactory);
        httpsJwks.setSimpleHttpGet(getInstance);

        // create new jwks key resolver, selects jwk based on key ID in jwt header
        HttpsJwksVerificationKeyResolver jwksKeyResolver
            = new HttpsJwksVerificationKeyResolver(httpsJwks);

        //create validation requirements
        JwtConsumer jwtConsumer =
            new JwtConsumerBuilder()
                .setRequireExpirationTime()         // has expiration time
                .setAllowedClockSkewInSeconds(30)   // leeway in validation time
                .setRequireSubject()                // has subject
                .setExpectedAudience(targetAudience)
                .setExpectedIssuer(dapsUrl)         // e.g. https://daps.aisec.fraunhofer.de
                .setVerificationKeyResolver(jwksKeyResolver) //get decryption key from jwks
                .setJweAlgorithmConstraints(
                    new AlgorithmConstraints(
                        ConstraintType.WHITELIST,
                        AlgorithmIdentifiers.RSA_USING_SHA256
                    )
                )
                .build();

        //verify dat
        JwtClaims claims;

        LOG.debug("Request JWKS from DAPS for validating DAT");

        try {
            claims = jwtConsumer.processToClaims(new String(dat));
        } catch (InvalidJwtException e) {
            LOG.error("DAPS response is not a valid DAT format", e);
            return -1;
        }

        //check security requirements
        if (securityRequirements != null) {
            LOG.debug("Validate security attributes");

            if (securityRequirements instanceof SecurityRequirements) {
                SecurityRequirements secRequirements = (SecurityRequirements) securityRequirements;
                SecurityRequirements providedSecurityProfile =
                    parseSecurityRequirements(claims.toJson());

                if (providedSecurityProfile == null) {
                    return -1;
                }

                //toDo add further security attribute validation
                if (secRequirements.getAuditLogging() <= providedSecurityProfile.getAuditLogging())
                {
                    LOG.info("DAT is valid and secure");
                    return 20;
                } else {
                    LOG.warn("DAT does not fulfill the security requirements");
                    return -1;
                }
            } else {
                //invalid security requirements format
                LOG.error("Invalid security requirements format. Expected SecurityRequirements.class");
                return -1;
            }
        } else {
            LOG.info("DAT is valid");
            return 20;
        }
    } //returns number of seconds dat is valid

    private SecurityRequirements parseSecurityRequirements(String dynamicAttrToken) {
        JSONObject asJson = new JSONObject(dynamicAttrToken);

        if (!asJson.has("ids_attributes")) {
            LOG.error("DAT does not contain ids_attributes");
            return null;
        }
        JSONObject idsAttributes = asJson.getJSONObject("ids_attributes");

        if (!idsAttributes.has("security_profile")) {
            LOG.error("DAT does not contain security_profile");
            return null;
        }
        JSONObject securityProfile = idsAttributes.getJSONObject("security_profile");

        //check if all security requirements are available
        if (!securityProfile.has("audit_logging")) {
            LOG.error("DAT does not contain audit_logging");
            return null;
        }

        //toDo parse further security attributes

        return new SecurityRequirements.Builder()
            .setAuditLogging(securityProfile.getInt("audit_logging"))
            .build();
    }
}

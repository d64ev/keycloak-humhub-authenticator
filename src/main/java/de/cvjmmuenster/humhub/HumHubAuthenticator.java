package de.cvjmmuenster.humhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialModel;
import org.keycloak.models.*;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.models.UserCredentialModel;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;

/**
 * HumHubAuthenticator: Handles login form rendering, local Keycloak password check,
 * fallback to HumHub API, and on-demand user import/sync.
 *
 * Usage:
 *  - Set as REQUIRED in the authentication flow.
 *  - Remove "Username Password Form" from the flow.
 */
public class HumHubAuthenticator implements Authenticator, AuthenticatorFactory {

    private static final Logger logger = Logger.getLogger(HumHubAuthenticator.class);

    // Logging toggle
    private static final boolean LOG_ENABLED = false;

    // Logging utilities
    private static void log(String msg) {
        if (LOG_ENABLED) logger.warn(msg);
    }
    private static void logf(String fmt, Object... args) {
        if (LOG_ENABLED) logger.warnf(fmt, args);
    }
    private static void logError(String msg, Throwable t) {
        if (LOG_ENABLED) logger.error(msg, t);
    }

    private static final String HUMHUB_API_URL = "https://your-humhub-instance/api/v1/auth/current";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * CredentialInput implementation for plain password checks in Keycloak's credentialManager().isValid()
     */
    public static class PasswordInput implements CredentialInput {
        private final String password;
        public PasswordInput(String password) { this.password = password; }
        @Override public String getType() { return CredentialModel.PASSWORD; }
        @Override public String getChallengeResponse() { return password; }
        public boolean isCredential() { return true; }
        @Override public String getCredentialId() { return null; }
    }

    /**
     * Main authentication logic:
     *  - Renders login form if no credentials are POSTed yet.
     *  - Tries local Keycloak credentials first.
     *  - Falls back to HumHub API if local fails. Imports or syncs user as needed.
     */
    @Override
    public void authenticate(AuthenticationFlowContext context) {
        log("HUMHUB: Authenticator called!");

        String login = context.getHttpRequest().getDecodedFormParameters().getFirst("username");
        String password = context.getHttpRequest().getDecodedFormParameters().getFirst("password");
        logf("HUMHUB: login='%s'", login, (password == null ? "NO" : "YES"));

        // 1. No credentials yet: render login form
        if (login == null || password == null) {
            log("HUMHUB: No credentials posted, showing login form.");
            context.challenge(context.form().createLoginUsernamePassword());
            return;
        }

        RealmModel realm = context.getRealm();
        KeycloakSession session = context.getSession();

        // 2. Attempt local user authentication (by username or email)
        UserModel user = session.users().getUserByUsername(realm, login);
        if (user == null && login.contains("@")) {
            user = session.users().getUserByEmail(realm, login);
        }
        boolean userExists = (user != null);
        logf("HUMHUB: User found locally? %s", userExists);

        boolean localAuthSuccess = false;
        if (userExists) {
            log("HUMHUB: Attempting local password check...");
            // Preferred way (Keycloak 21+):
            UserCredentialModel credential = UserCredentialModel.password(password, false);
            localAuthSuccess = user.credentialManager().isValid(credential);
            logf("HUMHUB: Local credential valid? %s", localAuthSuccess);
        }

        // 3. Local auth success: finish login
        if (userExists && localAuthSuccess) {
            log("HUMHUB: Local authentication succeeded.");
            context.setUser(user);
            context.success();
            return;
        }

        // 4. Fallback: Try HumHub API
        log("HUMHUB: Local login failed or user not found. Trying HumHub API...");
        HumHubUser humHubUser = authenticateWithHumHub(login, password);

        if (humHubUser == null) {
            log("HUMHUB: HumHub authentication failed. Showing error.");
            context.form().setError("Invalid username or password");
            context.challenge(context.form().createLoginUsernamePassword());
            return;
        }

        // 5. HumHub auth success: import or update user
        log("HUMHUB: HumHub authentication succeeded. Syncing user...");
        if (!userExists) {
            user = importUser(context, humHubUser, password);
            logf("HUMHUB: Imported user %s into Keycloak.", humHubUser.username);
        } else {
            updateUserFromHumHub(user, humHubUser, password);
            logf("HUMHUB: Updated user %s with HumHub data.", user.getUsername());
        }

        context.setUser(user);
        log("HUMHUB: context.success() called.");
        context.success();
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        authenticate(context);
    }

    // --- Authenticator SPI ---
    @Override public boolean requiresUser() { return false; }
    @Override public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) { return true; }
    @Override public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {}
    @Override public void close() {}
    @Override public boolean isUserSetupAllowed() { return false; }
    @Override public String getDisplayType() { return "HumHub Authenticator"; }
    @Override public String getReferenceCategory() { return "humhub-auth"; }
    @Override public boolean isConfigurable() { return false; }
    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return new AuthenticationExecutionModel.Requirement[] {
            AuthenticationExecutionModel.Requirement.ALTERNATIVE,
            AuthenticationExecutionModel.Requirement.REQUIRED
        };
    }
    @Override public String getHelpText() { return "Authenticator for Keycloak/HumHub hybrid login, on-demand user import, and credential sync."; }
    @Override public List<ProviderConfigProperty> getConfigProperties() { return new ArrayList<>(); }
    @Override public Authenticator create(KeycloakSession session) { return this; }
    @Override public void init(org.keycloak.Config.Scope config) {}
    @Override public void postInit(KeycloakSessionFactory factory) {}
    @Override public String getId() { return "humhub-authenticator"; }

    /**
     * Authenticates against the HumHub REST API and parses user info if successful.
     */
    private HumHubUser authenticateWithHumHub(String login, String password) {
        logf("HUMHUB: Calling HumHub API for login '%s'...", login);
        try {
            URL url = new URL(HUMHUB_API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            String auth = login + ":" + password;
            String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encoded);
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            logf("HUMHUB: HumHub HTTP status: %d", status);
            InputStream is = (status == HttpURLConnection.HTTP_OK)
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                String body = sb.toString();
                logf("HUMHUB: API response: %s", body);
                if (status == HttpURLConnection.HTTP_OK) {
                    JsonNode node = objectMapper.readTree(body);
                    return HumHubUser.fromJson(node);
                } else {
                    logf("HUMHUB: HumHub authentication failed for '%s': %s", login, body);
                    return null;
                }
            }
        } catch (Exception e) {
            logError("HUMHUB: Exception during HumHub communication", e);
            return null;
        }
    }

    /**
     * Imports a new user from HumHub into Keycloak and sets credentials.
     * Sets enabled/emailVerified.
     */
    private UserModel importUser(AuthenticationFlowContext context, HumHubUser humHubUser, String plainPassword) {
        RealmModel realm = context.getRealm();
        KeycloakSession session = context.getSession();
        UserModel user = session.users().addUser(realm, humHubUser.guid != null && !humHubUser.guid.isEmpty() ? humHubUser.guid : null);
        user.setUsername(humHubUser.username);
        user.setEmail(humHubUser.email);
        user.setFirstName(humHubUser.firstname);
        user.setLastName(humHubUser.lastname);
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setSingleAttribute("humhub_guid", humHubUser.guid);
        user.setSingleAttribute("humhub_display_name", humHubUser.displayName);
        user.setSingleAttribute("humhub_profile_url", humHubUser.profileUrl);
        user.setSingleAttribute("humhub_image_url", humHubUser.imageUrl);
        updateUserPassword(user, plainPassword);
        return user;
    }

    /**
     * Updates an existing Keycloak user with HumHub data and password.
     * Also sets enabled/emailVerified.
     */
    private void updateUserFromHumHub(UserModel user, HumHubUser humHubUser, String plainPassword) {
        user.setEmail(humHubUser.email);
        user.setFirstName(humHubUser.firstname);
        user.setLastName(humHubUser.lastname);
        user.setSingleAttribute("humhub_guid", humHubUser.guid);
        user.setSingleAttribute("humhub_display_name", humHubUser.displayName);
        user.setSingleAttribute("humhub_profile_url", humHubUser.profileUrl);
        user.setSingleAttribute("humhub_image_url", humHubUser.imageUrl);
        user.setEnabled(true);
        user.setEmailVerified(true);
        updateUserPassword(user, plainPassword);
    }

    /**
     * Updates the user's password credential using the Keycloak API (Keycloak 21+).
     */
    private void updateUserPassword(UserModel user, String plainPassword) {
        CredentialInput passwordInput = new CredentialInput() {
            @Override public String getType() { return CredentialModel.PASSWORD; }
            @Override public String getChallengeResponse() { return plainPassword; }
            public boolean isCredential() { return true; }
            @Override public String getCredentialId() { return null; }
        };
        user.credentialManager().updateCredential(passwordInput);
        log("HUMHUB: Updated password credential for user " + user.getUsername());
    }
}

/**
 * POJO for parsing HumHub API responses for user creation/sync.
 */
class HumHubUser {
    public String guid;
    public String username;
    public String email;
    public String firstname;
    public String lastname;
    public String displayName;
    public String profileUrl;
    public String imageUrl;

    public static HumHubUser fromJson(JsonNode node) {
        HumHubUser user = new HumHubUser();
        user.guid = node.path("guid").asText("");
        user.displayName = node.path("display_name").asText("");
        user.profileUrl = node.path("url").asText("");
        // Parse "account" object
        JsonNode account = node.path("account");
        user.username = account.path("username").asText("");
        user.email = account.path("email").asText("");
        // Parse "profile" object
        JsonNode profile = node.path("profile");
        user.firstname = profile.path("firstname").asText("");
        user.lastname = profile.path("lastname").asText("");
        user.imageUrl = profile.path("image_url").asText("");
        return user;
    }
}

# HumHub Authenticator for Keycloak

This project provides a custom Keycloak Authenticator that first tries to authenticate against the local Keycloak database. If the user doesn't exist locally or the password is invalid, it will try to authenticate the credentials against the HumHub REST API. When the credentials are valid for HumHub, the HumHub user will automatically be imported into Keycloak on-demand and the login will succeed. On the next login, that user will be a local Keycloak user.

The use case of this module is that you can disable public registration on HumHub and Keycloak, but still allow HumHub users to invite new users. New users can also be created by HumHub administrators. They will become Keycloak users once they log in using Keycloak.

---

## Requirements

* **HumHub** instance with the following plugins:

  * [RESTful API Plugin](https://marketplace.humhub.com/module/rest/description) (required for authentication by Keycloak Authenticator)
  * [Keycloak Sign-In Module](https://marketplace.humhub.com/module/auth-keycloak/description) (required for user login using Keycloak)
* **Keycloak** (tested with v26+)
* Java 17+
* Maven

---

## Installation & Build

1. **Clone this repository**

   ```sh
   git clone git@github.com:pegelf/keycloak-humhub-authenticator.git
   cd keycloak-humhub-authenticator
   ```

2. **Configure the HumHub API URL**

   * Edit `HumHubAuthenticator.java` **before building**:

   ```java
   private static final String HUMHUB_API_URL = "https://your-humhub-instance/api/v1/auth/current";
   ```

   * Replace with your actual HumHub URL (including HTTPS if enabled).

3. **Build the JAR**

   ```sh
   mvn clean package
   ```

   The JAR will be found in `target/humhub-authenticator-1.0.0.jar`.

4. **Copy the JAR to your Keycloak installation**

   * **If using LXC containers on Proxmox:**

     ```sh
     pct push <container-id> target/humhub-authenticator-1.0.0.jar /opt/keycloak/providers/humhub-authenticator-1.0.0.jar
     ```

     (Example: `pct push 105 target/humhub-authenticator-1.0.0.jar /opt/keycloak/providers/humhub-authenticator-1.0.0.jar`)

   * Or copy using `scp`, `rsync`, or another method as needed.

5. **Restart Keycloak**

   ```sh
   systemctl restart keycloak
   ```

   * **Monitor logs:**

     ```sh
     journalctl -fu keycloak
     ```

---

## Keycloak Flow Setup

**IMPORTANT:**

* The authenticator will only work if the login flow is correctly configured.
* You MUST remove the built-in "Username Password Form" from the flow.
* Clone the existing `browser` authentication flow first, then edit the clone.

**Steps:**

1. In the Keycloak Admin Console, open the correct realm and go to **Authentication → Flows**.
2. Clone the `browser` flow (or your relevant login flow).
3. Remove the step **"Username Password Form"**.
4. Add **"HumHub Authenticator"** as a REQUIRED execution at the position where "Username Password Form" was.
5. (Optional) You can keep the "OTP Form" after the HumHub Authenticator if you're using 2FA.
6. Set your realm or client to use this new flow by using the "Bind flow" action (top right button).

---

## Additional settings

On your HumHub instance, if you only want to use Keycloak for login and HumHub for registrations, you can hide elements that aren't needed so your users won't be confused:

```css
// hide non-keycloak login on login page
.login-container {
    #login-form .panel-body {
        .or-container,
        #account-login-form {
            display: none;
        }
    }
    // hide keycloak and other providers login on registration page
    #create-account-form .panel-body {
        .or-container,
        .authChoice {
            display: none;
        }
    }
}
```

To automatically redirect the login page to Keycloak, you can use this Nginx rule:

```nginx
location = /user/auth/login {
    return 302 /user/auth/external?authclient=Keycloak;
}
```

If you experience any issues with this redirection rule, feel free to open an issue.

---

## Troubleshooting

* **No login possible / No users imported:**

  * Check that your HumHub instance is reachable from the Keycloak server.
  * Ensure the RESTful API module is enabled and configured.
  * Watch the Keycloak logs for details:

    ```sh
    journalctl -fu keycloak
    ```

* **API errors:**

  * Make sure the HumHub RESTful API Plugin is up to date and working.
  * Check for authentication/permission issues in the HumHub admin panel.

* **Build errors (Keycloak classes not found):**

  * Make sure you have added the Keycloak Maven repository to your `pom.xml`:

    ```xml
    <repositories>
      <repository>
        <id>keycloak</id>
        <url>https://maven.keycloak.org/releases</url>
      </repository>
    </repositories>
    ```

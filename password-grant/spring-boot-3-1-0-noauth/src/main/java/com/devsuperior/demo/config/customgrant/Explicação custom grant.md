## How the "custom password grant" works (end-to-end)

When you call **POST /oauth2/token** with **grant_type=password**,
Spring Authorization Server will:

-   use your converter to translate the HTTP request into
    an **Authentication** object
-   pass that **Authentication** to your provider
-   the provider validates credentials, generates the access token
    (JWT), and returns an authenticated result

This project implements those pieces
in **com.devsuperior.demo.config.customgrant**.

## **CustomPasswordAuthenticationConverter** (request → Authentication)

File: **\.../customgrant/CustomPasswordAuthenticationConverter.java**

### **convert(HttpServletRequest request)**

-   Reads **grant_type** from the request.

-   If **grant_type** is not **\"password\"**, it returns **null** so
    other converters/providers can try (this is the standard "chain"
    behavior).

-   Collects parameters into
    a **MultiValueMap** via **getParameters(request)**.

-   Validates input according to OAuth2 token endpoint expectations:

    -   **scope** is optional, but if present it must appear exactly
        once
    -   **username** is required and must appear exactly once
    -   **password** is required and must appear exactly once
    -   otherwise
        throws **OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST)**

-   Parses scopes: splits the **scope** string by spaces into
    a **Set\<String\>**.

-   Builds **additionalParameters**: copies all parameters
    except **grant_type** and **scope** (so it
    includes **username** and **password**).

-   Gets the authenticated client from **SecurityContextHolder** (the
    client is authenticated earlier via client auth, e.g. Basic Auth).

-   Returns a **CustomPasswordAuthenticationToken(clientPrincipal,
    requestedScopes, additionalParameters)**.

### **getParameters(HttpServletRequest request)**

-   Converts the servlet **parameterMap** (**Map\<String,
    String\[\]\>**) into a **MultiValueMap\<String, String\>**.
-   This is mainly to make "exactly one value" validation
    easy/consistent.

## **CustomPasswordAuthenticationToken** (the grant "carrier" object)

File: **\.../customgrant/CustomPasswordAuthenticationToken.java**

This is the object produced by the converter and consumed by the
provider.

### Constructor

**CustomPasswordAuthenticationToken(Authentication clientPrincipal,
Set\<String\> scopes, Map\<String,Object\> additionalParameters)**

-   Calls the parent
    constructor **OAuth2AuthorizationGrantAuthenticationToken** with:

    -   **new AuthorizationGrantType(\"password\")**
    -   the client principal
    -   **additionalParameters**

-   Pulls **username** and **password** out
    of **additionalParameters** (expects
    keys **\"username\"** and **\"password\"**).

-   Stores scopes as an unmodifiable set.

### **getUsername()**, **getPassword()**, **getScopes()**

-   Simple accessors used by the provider.

## **CustomPasswordAuthenticationProvider** (Authentication → access token)

File: **\.../customgrant/CustomPasswordAuthenticationProvider.java**

This is where credential checking + token minting happens.

### Constructor

Receives required collaborators:

-   **OAuth2AuthorizationService**: where issued authorizations/tokens
    are stored (in your config it's in-memory)
-   **OAuth2TokenGenerator**: actually produces JWT/access tokens
-   **UserDetailsService**: loads user by username
-   **PasswordEncoder**: checks raw password vs stored hash

It asserts none are null.

### **authenticate(Authentication authentication)**

High-level flow:

1.  Casts the
    incoming **Authentication** to **CustomPasswordAuthenticationToken**.

2.  Validates client
    authentication with **getAuthenticatedClientElseThrowInvalidClient(\...)**.

    -   That returns an
        authenticated **OAuth2ClientAuthenticationToken**
    -   Extracts the **RegisteredClient** from it (client config:
        allowed scopes, grant types, etc.)

3.  Extracts user credentials from the custom token:

    -   **username = token.getUsername()**
    -   **password = token.getPassword()**

4.  Loads user via **userDetailsService.loadUserByUsername(username)**

    -   If not found, throws **OAuth2AuthenticationException(\"Invalid
        credentials\")**

5.  Verifies password using **passwordEncoder.matches(password,
    user.getPassword())**

    -   If mismatch, throws **OAuth2AuthenticationException(\"Invalid
        credentials\")**

6.  Computes authorized scopes:

    -   Starts from the user's authorities (strings
        like **\"read\"**, **\"write\"**, etc.)
    -   Keeps only those that are also
        in **registeredClient.getScopes()**
    -   Result becomes **authorizedScopes**

7.  Important customization hook (for JWT claims):

    -   Creates **CustomUserAuthorities(username,
        user.getAuthorities())**
    -   Stores it in the authenticated client token
        via **oAuth2ClientAuthenticationToken.setDetails(customPasswordUser)**
    -   Replaces the **SecurityContextHolder** context with one that
        contains this updated client authentication
    -   This is done
        so **AuthorizationServerConfig.tokenCustomizer()** can
        read **principal.getDetails()** and
        add **username**/**authorities** claims to the JWT.

8.  Builds token context (**DefaultOAuth2TokenContext**) and
    authorization record (**OAuth2Authorization.Builder**)

    -   Sets grant type to **new AuthorizationGrantType(\"password\")**
    -   Sets **authorizedScopes**

9.  Generates the access token:

    -   **tokenGenerator.generate(tokenContext)**
    -   Wraps it as an **OAuth2AccessToken** (bearer)
    -   If the generated token exposes claims (**ClaimAccessor**), it
        stores claims metadata in the authorization (so claims can be
        retrieved later if needed)

10. Saves the **OAuth2Authorization** via **authorizationService.save(authorization)**

11. Returns **OAuth2AccessTokenAuthenticationToken(\...)** which Spring
    uses as the successful outcome of **/oauth2/token**.

### **supports(Class\<?\> authentication)**

-   Returns **true** only for **CustomPasswordAuthenticationToken**.
-   This is how Spring picks this provider for your custom grant token.

### **getAuthenticatedClientElseThrowInvalidClient(Authentication authentication)**

-   Ensures **authentication.getPrincipal()** is
    an **OAuth2ClientAuthenticationToken** and **isAuthenticated() ==
    true**.
-   If not,
    throws **OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT)**.
-   In other words: your password grant only runs if the client itself
    already authenticated correctly.

## **CustomUserAuthorities** (data passed into JWT customization)

File: **\.../customgrant/CustomUserAuthorities.java**

A simple holder used as **principal.details**:

-   **username**
-   **authorities** (**Collection\<? extends GrantedAuthority\>**)

It exists mainly to support this in **AuthorizationServerConfig**:

-   **CustomUserAuthorities user = (CustomUserAuthorities)
    principal.getDetails();**
-   then it adds JWT claims **username** and **authorities**.

## Where it's wired in (**AuthorizationServerConfig**)

In **AuthorizationServerConfig.asSecurityFilterChain(\...)** you
configured the token endpoint to use your custom pieces:

-   **.accessTokenRequestConverter(new
    CustomPasswordAuthenticationConverter())**

    -   tells the token endpoint how to
        interpret **grant_type=password** requests

-   **.authenticationProvider(new
    CustomPasswordAuthenticationProvider(\...))**

    -   tells the token endpoint how to authenticate that request and
        mint tokens

And in **tokenCustomizer()**:

-   reads **principal.getDetails()** (which your provider sets)
-   adds **username** and **authorities** into the access token JWT
    claims

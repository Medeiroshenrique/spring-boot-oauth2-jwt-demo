## Custom password grant (Spring Authorization Server) — classes and methods

This project implements a **custom `grant_type=password`** for Spring Authorization Server by providing:

- an **`AuthenticationConverter`** (HTTP request → Authentication object)
- an **`AuthenticationProvider`** (Authentication object → validate credentials → mint access token)
- a custom **`OAuth2AuthorizationGrantAuthenticationToken`** (the carrier object between converter and provider)
- a small **details** object used to inject user info into JWT claims

The custom grant code lives under:

`src/main/java/com/devsuperior/demo/config/customgrant/`

---

## End-to-end flow (what happens when you call `/oauth2/token`)

When you send:

- `POST /oauth2/token`
- with `grant_type=password`
- and the client authenticates (e.g., HTTP Basic with `client_id`/`client_secret`)

Spring Authorization Server will, in order:

1. Authenticate the **client** first (you end up with an `OAuth2ClientAuthenticationToken` in the security context).
2. Use your configured `accessTokenRequestConverter` to attempt conversion of the request.
3. If the converter returns a custom `Authentication` token, Spring will pass it to a matching `AuthenticationProvider`.
4. The provider validates the resource owner credentials (`username`/`password`), computes scopes, generates an access token (JWT), saves the authorization, and returns success.

Your wiring is in `AuthorizationServerConfig`:

- `tokenEndpoint(...).accessTokenRequestConverter(new CustomPasswordAuthenticationConverter())`
- `tokenEndpoint(...).authenticationProvider(new CustomPasswordAuthenticationProvider(...))`

---

## `CustomPasswordAuthenticationConverter` (request → Authentication)

File: `CustomPasswordAuthenticationConverter.java`

### `convert(HttpServletRequest request)`

Purpose: transform a token endpoint HTTP request into an `Authentication` object that represents your custom grant.

Key steps:

- **Check `grant_type`**:
  - Reads `grant_type` from `request.getParameter(OAuth2ParameterNames.GRANT_TYPE)`.
  - If it’s not `"password"`, it returns **`null`**.
    - This is important: returning `null` tells Spring “this converter doesn’t handle this request”, so other converters can try.

- **Read parameters into a MultiValueMap**:
  - Calls `getParameters(request)` to normalize parameters (helps with “must appear exactly once” checks).

- **Validate required parameters**:
  - `scope` is optional, but if present it must appear **exactly once**.
  - `username` is required and must appear **exactly once**.
  - `password` is required and must appear **exactly once**.
  - If any of those rules fail it throws:
    - `new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST)`

- **Parse scopes**:
  - If `scope` is present, it splits by spaces into a `Set<String>`.

- **Build `additionalParameters`**:
  - Copies all parameters except `grant_type` and `scope`.
  - This includes **`username`** and **`password`** (and would include anything else you add later).

- **Use the already-authenticated client principal**:
  - Reads the current authentication from `SecurityContextHolder.getContext().getAuthentication()`.
  - This should be the client authentication (not the end-user).

- **Return custom Authentication token**:
  - Creates and returns `CustomPasswordAuthenticationToken(clientPrincipal, requestedScopes, additionalParameters)`.

### `getParameters(HttpServletRequest request)`

Purpose: normalize servlet request parameters into a `MultiValueMap<String,String>`:

- Reads `request.getParameterMap()` (`Map<String, String[]>`)
- Adds each value into a `LinkedMultiValueMap`

This makes it easy to check “is this parameter present?” and “does it have exactly one value?”.

---

## `CustomPasswordAuthenticationToken` (the grant “carrier” object)

File: `CustomPasswordAuthenticationToken.java`

This is the `Authentication` object produced by the converter and consumed by the provider.

It extends `OAuth2AuthorizationGrantAuthenticationToken`, which is the standard base type Spring Authorization Server uses for “authorization grant requests”.

### Constructor

`CustomPasswordAuthenticationToken(Authentication clientPrincipal, Set<String> scopes, Map<String, Object> additionalParameters)`

What it does:

- Calls the superclass constructor with:
  - `new AuthorizationGrantType("password")`
  - the `clientPrincipal`
  - `additionalParameters`
- Extracts:
  - `username` from `additionalParameters.get("username")`
  - `password` from `additionalParameters.get("password")`
- Stores the scopes as an **unmodifiable set**.

### `getUsername()`, `getPassword()`, `getScopes()`

Simple getters used by the provider during authentication and token creation.

---

## `CustomPasswordAuthenticationProvider` (Authentication → validate → token)

File: `CustomPasswordAuthenticationProvider.java`

This is the core of the custom grant implementation.

### Constructor

Receives and stores the required collaborators:

- `OAuth2AuthorizationService authorizationService`
  - used to persist `OAuth2Authorization` records (here it’s in-memory)
- `OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator`
  - generates JWT/access token values
- `UserDetailsService userDetailsService`
  - loads your application user
- `PasswordEncoder passwordEncoder`
  - validates raw password vs stored encoded password

Uses `Assert.notNull(...)` to fail fast if anything is missing.

### `authenticate(Authentication authentication)`

Purpose: validate the custom grant token and return a successful authentication containing an access token.

Steps:

1. **Cast** to `CustomPasswordAuthenticationToken`.

2. **Validate the client**:
   - Calls `getAuthenticatedClientElseThrowInvalidClient(...)`
   - Ensures the principal is an authenticated `OAuth2ClientAuthenticationToken`
   - If not, throws `OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT)`

3. **Get the RegisteredClient**
   - `RegisteredClient registeredClient = clientPrincipal.getRegisteredClient();`
   - Contains allowed scopes and allowed grant types for the client.

4. **Read user credentials**
   - `username = customToken.getUsername()`
   - `password = customToken.getPassword()`

5. **Load user**
   - `userDetailsService.loadUserByUsername(username)`
   - If it throws `UsernameNotFoundException`, provider throws `OAuth2AuthenticationException("Invalid credentials")`

6. **Verify password**
   - `passwordEncoder.matches(password, user.getPassword())`
   - Also checks username equality
   - On mismatch throws `OAuth2AuthenticationException("Invalid credentials")`

7. **Compute authorized scopes**
   - Takes the user authorities (strings)
   - Filters to only those present in `registeredClient.getScopes()`
   - Result is `authorizedScopes`

8. **Put user info into `SecurityContextHolder` details (for JWT customization)**
   - Creates `CustomUserAuthorities(username, user.getAuthorities())`
   - Sets it into the client authentication token via `oAuth2ClientAuthenticationToken.setDetails(customPasswordUser)`
   - Replaces the security context with this updated authentication
   - This is how `AuthorizationServerConfig.tokenCustomizer()` later reads `principal.getDetails()` and adds `username` and `authorities` claims into the JWT.

9. **Build token context**
   - Uses `DefaultOAuth2TokenContext.builder()` and sets:
     - `registeredClient`
     - `principal(clientPrincipal)`
     - `authorizedScopes`
     - `authorizationGrantType(new AuthorizationGrantType("password"))`
     - `authorizationGrant(customPasswordAuthenticationToken)`

10. **Build authorization record**
   - Uses `OAuth2Authorization.withRegisteredClient(...)`
   - Sets principal name, grant type, scopes, etc.

11. **Generate access token**
   - Builds a context with `tokenType(OAuth2TokenType.ACCESS_TOKEN)`
   - Calls `tokenGenerator.generate(tokenContext)`
   - If it returns null, throws a server error `OAuth2AuthenticationException`
   - Wraps result into an `OAuth2AccessToken`
   - If the generated token supports `ClaimAccessor`, it stores claims metadata.

12. **Save authorization**
   - `authorizationService.save(authorization)`

13. **Return success**
   - Returns `new OAuth2AccessTokenAuthenticationToken(registeredClient, clientPrincipal, accessToken)`
   - This is what the token endpoint uses to build the HTTP response.

### `supports(Class<?> authentication)`

Returns true for `CustomPasswordAuthenticationToken` so Spring knows this provider can handle that token type.

### `getAuthenticatedClientElseThrowInvalidClient(Authentication authentication)`

Helper that:

- checks that `authentication.getPrincipal()` is an `OAuth2ClientAuthenticationToken`
- checks `isAuthenticated()`
- otherwise throws `OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT)`

This ensures **client authentication happens first**, before user password validation.

---

## `CustomUserAuthorities` (details object used by JWT customizer)

File: `CustomUserAuthorities.java`

This is a simple POJO holding:

- `username`
- `authorities` (a `Collection<? extends GrantedAuthority>`)

It’s stored as `principal.details` so the JWT customizer can read it and add claims.

---

## Where JWT claims are added (`AuthorizationServerConfig.tokenCustomizer`)

In `AuthorizationServerConfig.tokenCustomizer()`:

- gets `OAuth2ClientAuthenticationToken principal = context.getPrincipal()`
- reads `CustomUserAuthorities user = (CustomUserAuthorities) principal.getDetails()`
- if token type is `"access_token"`, adds claims:
  - `authorities`
  - `username`

That’s why the provider sets `principal.setDetails(customPasswordUser)` before generating the token.


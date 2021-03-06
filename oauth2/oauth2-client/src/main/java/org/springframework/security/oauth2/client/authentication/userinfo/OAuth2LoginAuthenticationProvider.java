/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.oauth2.client.authentication.userinfo;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.authentication.AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.client.authentication.AuthorizationGrantTokenExchanger;
import org.springframework.security.oauth2.client.authentication.AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.TokenResponse;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.util.Assert;

import java.util.Collection;

/**
 * An implementation of an {@link AuthenticationProvider}
 * for the <i>OAuth 2.0 Authorization Code Grant Flow</i>.
 *
 * This {@link AuthenticationProvider} is responsible for authenticating
 * an <i>authorization code</i> credential with the authorization server's <i>Token Endpoint</i>
 * and if valid, exchanging it for an <i>access token</i> credential.
 * <p>
 * It will also obtain the user attributes of the <i>End-User</i> (resource owner)
 * from the <i>UserInfo Endpoint</i> using an {@link OAuth2UserService}
 * which will create a <code>Principal</code> in the form of an {@link OAuth2User}.
 *
 * @author Joe Grandja
 * @since 5.0
 * @see AuthorizationCodeAuthenticationToken
 * @see OAuth2AuthenticationToken
 * @see AuthorizedClient
 * @see OAuth2UserService
 * @see OAuth2User
 * @see <a target="_blank" href="https://tools.ietf.org/html/rfc6749#section-4.1">Section 4.1 Authorization Code Grant Flow</a>
 * @see <a target="_blank" href="https://tools.ietf.org/html/rfc6749#section-4.1.3">Section 4.1.3 Access Token Request</a>
 * @see <a target="_blank" href="https://tools.ietf.org/html/rfc6749#section-4.1.4">Section 4.1.4 Access Token Response</a>
 */
public class OAuth2LoginAuthenticationProvider implements AuthenticationProvider {
	private static final String INVALID_STATE_PARAMETER_ERROR_CODE = "invalid_state_parameter";
	private static final String INVALID_REDIRECT_URI_PARAMETER_ERROR_CODE = "invalid_redirect_uri_parameter";
	private final AuthorizationGrantTokenExchanger<AuthorizationCodeAuthenticationToken> authorizationCodeTokenExchanger;
	private final OAuth2UserService userService;
	private GrantedAuthoritiesMapper authoritiesMapper = (authorities -> authorities);

	public OAuth2LoginAuthenticationProvider(
		AuthorizationGrantTokenExchanger<AuthorizationCodeAuthenticationToken> authorizationCodeTokenExchanger,
		OAuth2UserService userService) {

		Assert.notNull(authorizationCodeTokenExchanger, "authorizationCodeTokenExchanger cannot be null");
		Assert.notNull(userService, "userService cannot be null");
		this.authorizationCodeTokenExchanger = authorizationCodeTokenExchanger;
		this.userService = userService;
	}

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		AuthorizationCodeAuthenticationToken authorizationCodeAuthentication =
				(AuthorizationCodeAuthenticationToken) authentication;

		// Section 3.1.2.1 Authentication Request - http://openid.net/specs/openid-connect-core-1_0.html#AuthRequest
		// scope
		// 		REQUIRED. OpenID Connect requests MUST contain the "openid" scope value.
		if (authorizationCodeAuthentication.getAuthorizationExchange()
			.getAuthorizationRequest().getScopes().contains("openid")) {
			// This is an OpenID Connect Authentication Request so return null
			// and let OidcAuthorizationCodeAuthenticationProvider handle it instead
			return null;
		}

		AuthorizationRequest authorizationRequest = authorizationCodeAuthentication
			.getAuthorizationExchange().getAuthorizationRequest();
		AuthorizationResponse authorizationResponse = authorizationCodeAuthentication
			.getAuthorizationExchange().getAuthorizationResponse();

		if (authorizationResponse.statusError()) {
			throw new OAuth2AuthenticationException(
				authorizationResponse.getError(), authorizationResponse.getError().toString());
		}

		if (!authorizationResponse.getState().equals(authorizationRequest.getState())) {
			OAuth2Error oauth2Error = new OAuth2Error(INVALID_STATE_PARAMETER_ERROR_CODE);
			throw new OAuth2AuthenticationException(oauth2Error, oauth2Error.toString());
		}

		if (!authorizationResponse.getRedirectUri().equals(authorizationRequest.getRedirectUri())) {
			OAuth2Error oauth2Error = new OAuth2Error(INVALID_REDIRECT_URI_PARAMETER_ERROR_CODE);
			throw new OAuth2AuthenticationException(oauth2Error, oauth2Error.toString());
		}

		TokenResponse tokenResponse =
			this.authorizationCodeTokenExchanger.exchange(authorizationCodeAuthentication);

		AccessToken accessToken = new AccessToken(tokenResponse.getTokenType(),
			tokenResponse.getTokenValue(), tokenResponse.getIssuedAt(),
			tokenResponse.getExpiresAt(), tokenResponse.getScopes());

		AuthorizedClient authorizedClient = new AuthorizedClient(
			authorizationCodeAuthentication.getClientRegistration(), "unknown", accessToken);

		OAuth2User oauth2User = this.userService.loadUser(authorizedClient);

		// Update AuthorizedClient now that we know the 'principalName'
		authorizedClient = new AuthorizedClient(
			authorizationCodeAuthentication.getClientRegistration(), oauth2User.getName(), accessToken);

		Collection<? extends GrantedAuthority> mappedAuthorities =
			this.authoritiesMapper.mapAuthorities(oauth2User.getAuthorities());

		OAuth2AuthenticationToken authenticationResult = new OAuth2AuthenticationToken(
			oauth2User, mappedAuthorities, authorizedClient);
		authenticationResult.setDetails(authorizationCodeAuthentication.getDetails());

		return authenticationResult;
	}

	public final void setAuthoritiesMapper(GrantedAuthoritiesMapper authoritiesMapper) {
		Assert.notNull(authoritiesMapper, "authoritiesMapper cannot be null");
		this.authoritiesMapper = authoritiesMapper;
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return AuthorizationCodeAuthenticationToken.class.isAssignableFrom(authentication);
	}
}

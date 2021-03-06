/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.springframework.test.web.servlet.htmlunit;

import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletContext;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.springframework.beans.Mergeable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.SmartRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.Assert;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.gargoylesoftware.htmlunit.CookieManager;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.util.NameValuePair;

/**
 * <p>
 * Internal class used to allow a {@link WebRequest} into a {@link MockHttpServletRequest} using Spring MVC Test's
 * {@link RequestBuilder}.
 * </p>
 * <p>
 * By default the first path segment of the URL is used as the contextPath. To override this default see
 * {@link #setContextPath(String)}.
 * </p>
 *
 * @author Rob Winch
 * @see MockMvcWebConnection
 */
final class HtmlUnitRequestBuilder implements RequestBuilder, Mergeable {
	private final Map<String, MockHttpSession> sessions;

	private final CookieManager cookieManager;

	private final WebRequest webRequest;

	private String contextPath;

	private RequestBuilder parentBuilder;

	private SmartRequestBuilder parentPostProcessor;

	private RequestPostProcessor forwardPostProcessor;

	/**
	 *
	 * @param sessions A {@link Map} of the {@link HttpSession#getId()} to currently managed {@link HttpSession}
	 * objects. Cannot be null.
	 * @param cookieManager The {@link CookieManager} used for managing {@link HttpSession}'s JSESSIONID cookie.
	 * @param webRequest The {@link WebRequest} to transform into a {@link MockHttpServletRequest}. Cannot be null.
	 */
	public HtmlUnitRequestBuilder(Map<String, MockHttpSession> sessions, CookieManager cookieManager,
			WebRequest webRequest) {
		Assert.notNull(sessions, "sessions cannot be null");
		Assert.notNull(cookieManager, "cookieManager");
		Assert.notNull(webRequest, "webRequest cannot be null");

		this.sessions = sessions;
		this.cookieManager = cookieManager;
		this.webRequest = webRequest;
	}

	public MockHttpServletRequest buildRequest(ServletContext servletContext) {
		String charset = getCharset();
		String httpMethod = webRequest.getHttpMethod().name();
		UriComponents uriComponents = uriComponents();

		MockHttpServletRequest result = new HtmlUnitMockHttpServletRequest(servletContext, httpMethod,
				uriComponents.getPath());
		parent(result, parentBuilder);
		result.setServerName(uriComponents.getHost()); // needs to be first for additional headers
		authType(result);
		result.setCharacterEncoding(charset);
		content(result, charset);
		contextPath(result, uriComponents);
		contentType(result);
		cookies(result);
		headers(result);
		locales(result);
		servletPath(uriComponents, result);
		params(result, uriComponents);
		ports(uriComponents, result);
		result.setProtocol("HTTP/1.1");
		result.setQueryString(uriComponents.getQuery());
		result.setScheme(uriComponents.getScheme());
		pathInfo(uriComponents,result);

		return postProcess(result);
	}

	private MockHttpServletRequest postProcess(MockHttpServletRequest request) {
		if(parentPostProcessor != null) {
			request = parentPostProcessor.postProcessRequest(request);
		}
		if(forwardPostProcessor != null) {
			request = forwardPostProcessor.postProcessRequest(request);
		}

		return request;
	}

	private void parent(MockHttpServletRequest result, RequestBuilder parent) {
		if(parent == null) {
			return;
		}
		MockHttpServletRequest parentRequest = parent.buildRequest(result.getServletContext());

		// session
		HttpSession parentSession = parentRequest.getSession(false);
		if(parentSession != null) {
			Enumeration<String> attrNames = parentSession.getAttributeNames();
			while(attrNames.hasMoreElements()) {
				String attrName = attrNames.nextElement();
				Object attrValue = parentSession.getAttribute(attrName);
				result.getSession().setAttribute(attrName, attrValue);
			}
		}

		// header
		Enumeration<String> headerNames = parentRequest.getHeaderNames();
		while(headerNames.hasMoreElements()) {
			String attrName = headerNames.nextElement();
			Enumeration<String> attrValues = parentRequest.getHeaders(attrName);
			while(attrValues.hasMoreElements()) {
				String attrValue = attrValues.nextElement();
				result.addHeader(attrName, attrValue);
			}
		}

		// parameter
		Map<String, String[]> parentParams = parentRequest.getParameterMap();
		for(Map.Entry<String,String[]> parentParam : parentParams.entrySet()) {
			String paramName = parentParam.getKey();
			String[] paramValues = parentParam.getValue();
			result.addParameter(paramName, paramValues);
		}

		// cookie
		Cookie[] parentCookies = parentRequest.getCookies();
		if(parentCookies != null) {
			result.setCookies(parentCookies);
		}

		// request attribute
		Enumeration<String> parentAttrNames = parentRequest.getAttributeNames();
		while(parentAttrNames.hasMoreElements()) {
			String parentAttrName = parentAttrNames.nextElement();
			result.setAttribute(parentAttrName, parentRequest.getAttribute(parentAttrName));
		}
	}

	/**
	 * Sets the contextPath to be used. The value may be null in which case the first path segment of the URL is turned
	 * into the contextPath. Otherwise it must conform to {@link HttpServletRequest#getContextPath()} which states it
	 * can be empty string or it must start with a "/" and not end in a "/".
	 *
	 * @param contextPath A valid contextPath
	 * @throws IllegalArgumentException if contextPath is not a valid {@link HttpServletRequest#getContextPath()}.
	 */
	public void setContextPath(String contextPath) {
		if (contextPath == null || "".equals(contextPath)) {
			this.contextPath = contextPath;
			return;
		}
		if (contextPath.endsWith("/")) {
			throw new IllegalArgumentException("contextPath cannot end with /. Got '" + contextPath + "'");
		}
		if (!contextPath.startsWith("/")) {
			throw new IllegalArgumentException("contextPath must start with /. Got '" + contextPath + "'");
		}
		this.contextPath = contextPath;
	}

	public void setForwardPostProcessor(RequestPostProcessor postProcessor) {
		this.forwardPostProcessor = postProcessor;
	}

	private void authType(MockHttpServletRequest request) {
		String authorization = header("Authorization");
		if (authorization != null) {
			String[] authzParts = authorization.split(": ");
			request.setAuthType(authzParts[0]);
		}
	}

	private void content(MockHttpServletRequest result, String charset) {
		String requestBody = webRequest.getRequestBody();
		if (requestBody == null) {
			return;
		}
		try {
			result.setContent(requestBody.getBytes(charset));
		}
		catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

	private void contentType(MockHttpServletRequest result) {
		String contentType = header("Content-Type");
		result.setContentType(contentType == null ? MediaType.ALL_VALUE.toString() : contentType);
	}

	private void contextPath(MockHttpServletRequest result, UriComponents uriComponents) {
		if (contextPath == null) {
			List<String> pathSegments = uriComponents.getPathSegments();
			if (pathSegments.isEmpty()) {
				result.setContextPath("");
			}
			else {
				result.setContextPath("/" + pathSegments.get(0));
			}
		}
		else {
			if (!uriComponents.getPath().startsWith(contextPath)) {
				throw new IllegalArgumentException(uriComponents.getPath() + " should start with contextPath "
						+ contextPath);
			}
			result.setContextPath(contextPath);
		}
	}

	private void cookies(MockHttpServletRequest result) {
		String cookieHeaderValue = header("Cookie");
		Cookie[] parentCookies = result.getCookies();
		List<Cookie> cookies = new ArrayList<Cookie>();
		if (cookieHeaderValue != null) {
			StringTokenizer tokens = new StringTokenizer(cookieHeaderValue, "=;");
			while (tokens.hasMoreTokens()) {
				String cookieName = tokens.nextToken().trim();
				if (!tokens.hasMoreTokens()) {
					throw new IllegalArgumentException("Expected value for cookie name " + cookieName
							+ ". Full cookie was " + cookieHeaderValue);
				}
				String cookieValue = tokens.nextToken().trim();
				processCookie(result, cookies, new Cookie(cookieName, cookieValue));
			}
		}

		Set<com.gargoylesoftware.htmlunit.util.Cookie> managedCookies = cookieManager.getCookies(webRequest.getUrl());
		for (com.gargoylesoftware.htmlunit.util.Cookie cookie : managedCookies) {
			processCookie(result, cookies, new Cookie(cookie.getName(), cookie.getValue()));
		}
		if(parentCookies != null) {
			for(Cookie cookie : parentCookies) {
				cookies.add(cookie);
			}
		}
		if (!cookies.isEmpty()) {
			result.setCookies(cookies.toArray(new Cookie[0]));
		}
	}

	private void processCookie(MockHttpServletRequest result, List<Cookie> cookies, Cookie cookie) {
		cookies.add(cookie);
		if ("JSESSIONID".equals(cookie.getName())) {
			result.setRequestedSessionId(cookie.getValue());
			result.setSession(httpSession(result, cookie.getValue()));
		}
	}

	private String getCharset() {
		String charset = webRequest.getCharset();
		if (charset == null) {
			return "ISO-8859-1";
		}
		return charset;
	}

	private String header(String headerName) {
		return webRequest.getAdditionalHeaders().get(headerName);
	}

	private void headers(MockHttpServletRequest result) {
		for (Entry<String, String> header : webRequest.getAdditionalHeaders().entrySet()) {
			result.addHeader(header.getKey(), header.getValue());
		}
	}

	private MockHttpSession httpSession(MockHttpServletRequest request, final String sessionid) {
		MockHttpSession session;
		synchronized (sessions) {
			session = sessions.get(sessionid);
			if (session == null) {
				session = new HtmlUnitMockHttpSession(request, sessionid);
				session.setNew(true);
				synchronized (sessions) {
					sessions.put(sessionid, session);
				}
				addSessionCookie(request, sessionid);
			}
			else {
				session.setNew(false);
			}
		}
		return session;
	}

	private void addSessionCookie(MockHttpServletRequest request, String sessionid) {
		cookieManager.addCookie(createCookie(request, sessionid));
	}

	private void removeSessionCookie(MockHttpServletRequest request, String sessionid) {
		cookieManager.removeCookie(createCookie(request, sessionid));
	}

	private com.gargoylesoftware.htmlunit.util.Cookie createCookie(MockHttpServletRequest request, String sessionid) {
		return new com.gargoylesoftware.htmlunit.util.Cookie(request.getServerName(), "JSESSIONID", sessionid,
				request.getContextPath() + "/", null, request.isSecure(), true);
	}

	private void locales(MockHttpServletRequest result) {
		String locale = header("Accept-Language");
		if (locale == null) {
			result.addPreferredLocale(Locale.getDefault());
		}
		else {
			String[] locales = locale.split(", ");
			for (int i = locales.length - 1; i >= 0; i--) {
				result.addPreferredLocale(parseLocale(locales[i]));
			}
		}
	}

	private void params(MockHttpServletRequest result, UriComponents uriComponents) {
		for (Entry<String, List<String>> values : uriComponents.getQueryParams().entrySet()) {
			String name = values.getKey();
			for (String value : values.getValue()) {
				try {
					result.addParameter(name, URLDecoder.decode(value, "UTF-8"));
				}
				catch (UnsupportedEncodingException e) {
					throw new RuntimeException(e);
				}
			}
		}
		for (NameValuePair param : webRequest.getRequestParameters()) {
			result.addParameter(param.getName(), param.getValue());
		}
	}

	private Locale parseLocale(String locale) {
		Matcher matcher = LOCALE_PATTERN.matcher(locale);
		if (!matcher.matches()) {
			throw new IllegalArgumentException("Invalid locale " + locale);
		}
		String language = matcher.group(1);
		String country = matcher.group(2);
		if (country == null) {
			country = "";
		}
		String qualifier = matcher.group(3);
		if (qualifier == null) {
			qualifier = "";
		}
		return new Locale(language, country, qualifier);
	}

	private void pathInfo(UriComponents uriComponents, MockHttpServletRequest result) {
		result.setPathInfo(null);
	}

	private void servletPath(MockHttpServletRequest result, String requestPath) {
		String servletPath = requestPath.substring(result.getContextPath().length());
		if ("".equals(servletPath)) {
			servletPath = null;
		}
		result.setServletPath(servletPath);
	}

	private void servletPath(UriComponents uriComponents, MockHttpServletRequest result) {
		if ("".equals(result.getPathInfo())) {
			result.setPathInfo(null);
		}
		servletPath(result, uriComponents.getPath());
	}

	private void ports(UriComponents uriComponents, MockHttpServletRequest result) {
		int serverPort = uriComponents.getPort();
		result.setServerPort(serverPort);
		if (serverPort == -1) {
			int portConnection = webRequest.getUrl().getDefaultPort();
			result.setLocalPort(serverPort);
			result.setRemotePort(portConnection);
		}
		else {
			result.setRemotePort(serverPort);
		}
	}

	private UriComponents uriComponents() {
		URL url = webRequest.getUrl();
		UriComponentsBuilder uriBldr = UriComponentsBuilder.fromUriString(url.toExternalForm());
		return uriBldr.build();
	}

    @Override
    public boolean isMergeEnabled() {
        return true;
    }

    @Override
    public Object merge(Object parent) {
        if (parent == null) {
            return this;
        }
		if(parent instanceof RequestBuilder) {
			this.parentBuilder = (RequestBuilder) parent;
		}
        if (parent instanceof SmartRequestBuilder) {
			this.parentPostProcessor = (SmartRequestBuilder) parent;
        }

        return this;
    }

    /**
	 * An extension to {@link MockHttpServletRequest} that ensures that when a new {@link HttpSession} is created, it is
	 * added to the managed sessions.
	 *
	 * @author Rob Winch
	 */
	private final class HtmlUnitMockHttpServletRequest extends MockHttpServletRequest {
		private HtmlUnitMockHttpServletRequest(ServletContext servletContext, String method, String requestURI) {
			super(servletContext, method, requestURI);
		}

		public HttpSession getSession(boolean create) {
			HttpSession result = super.getSession(false);
			if (result == null && create) {
				HtmlUnitMockHttpSession newSession = new HtmlUnitMockHttpSession(this);
				setSession(newSession);
				newSession.setNew(true);
				String sessionid = newSession.getId();
				synchronized (sessions) {
					sessions.put(sessionid, newSession);
				}
				addSessionCookie(this, sessionid);
				result = newSession;
			}
			return result;
		}

		public HttpSession getSession() {
			return super.getSession();
		}

		public void setSession(HttpSession session) {
			super.setSession(session);
		}
	}

	/**
	 * An extension to {@link MockHttpSession} that ensures when {@link #invalidate()} is called that the
	 * {@link HttpSession} is removed from the managed sessions.
	 *
	 * @author Rob Winch
	 */
	private final class HtmlUnitMockHttpSession extends MockHttpSession {
		private final MockHttpServletRequest request;

		private HtmlUnitMockHttpSession(MockHttpServletRequest request) {
			super(request.getServletContext());
			this.request = request;
		}

		private HtmlUnitMockHttpSession(MockHttpServletRequest request, String id) {
			super(request.getServletContext(), id);
			this.request = request;
		}

		public void invalidate() {
			super.invalidate();
			synchronized (sessions) {
				sessions.remove(getId());
			}
			removeSessionCookie(request, getId());
		}
	}

	private static final Pattern LOCALE_PATTERN = Pattern.compile("^\\s*(\\w{2})(?:-(\\w{2}))?(?:;q=(\\d+\\.\\d+))?$");
}

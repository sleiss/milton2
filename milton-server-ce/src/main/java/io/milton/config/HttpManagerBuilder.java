/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.milton.config;

import io.milton.annotations.ResourceController;
import io.milton.property.PropertySource;
import io.milton.common.Stoppable;
import io.milton.event.EventManager;
import io.milton.event.EventManagerImpl;
import io.milton.http.*;
import io.milton.http.annotated.AnnotationResourceFactory;
import io.milton.http.annotated.AnnotationResourceFactory.AnnotationsDisplayNameFormatter;
import io.milton.http.entity.DefaultEntityTransport;
import io.milton.http.entity.EntityTransport;
import io.milton.http.fck.FckResourceFactory;
import io.milton.http.fs.FileContentService;
import io.milton.http.fs.FileSystemResourceFactory;
import io.milton.http.fs.SimpleFileContentService;
import io.milton.http.fs.SimpleSecurityManager;
import io.milton.http.http11.*;
import io.milton.http.http11.DefaultHttp11ResponseHandler.BUFFERING;
import io.milton.http.http11.auth.*;
import io.milton.http.http11.auth.LoginResponseHandler.LoginPageTypeHandler;
import io.milton.http.json.JsonPropFindHandler;
import io.milton.http.json.JsonPropPatchHandler;
import io.milton.http.json.JsonResourceFactory;
import io.milton.http.quota.QuotaDataAccessor;
import io.milton.http.values.ValueWriters;
import io.milton.http.webdav.*;
import io.milton.property.*;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the options for configuring a HttpManager. To use it just set
 * properties on this class, then call init, then call buildHttpManager to get a
 * reference to the HttpManager.
 *
 * Note that this uses a two-step construction process: init()
 * buildHttpManager()
 *
 * The first step creates instances of any objects which have not been set and
 * the second binds them onto the HttpManager. You might want to modify the
 * objects created in the first step, eg setting properties on default
 * implementations. Note that you should not modify the structure of the
 * resultant object graph, because you could then end up with an inconsistent
 * configuration
 *
 * Where possible, default implementations are created when this class is
 * constructed allowing them to be overwritten where needed. However this is
 * only done for objects and values which are "leaf" nodes in the config object
 * graph. This is to avoid inconsistent configuration where different parts of
 * milton end up with different implementations of the same concern. For
 * example, PropFind and PropPatch could end up using different property sources
 *
 * @author brad
 */
public class HttpManagerBuilder {

	private static final Logger log = LoggerFactory.getLogger(HttpManagerBuilder.class);
	protected List<InitListener> listeners;
	protected ResourceFactory mainResourceFactory;
	protected ResourceFactory outerResourceFactory;
	protected FileContentService fileContentService = new SimpleFileContentService(); // Used for FileSystemResourceFactory
	protected DefaultHttp11ResponseHandler.BUFFERING buffering;
	protected List<AuthenticationHandler> authenticationHandlers;
	protected List<AuthenticationHandler> extraAuthenticationHandlers;
	protected List<AuthenticationHandler> cookieDelegateHandlers;
	protected DigestAuthenticationHandler digestHandler;
	protected BasicAuthHandler basicHandler;
	protected CookieAuthenticationHandler cookieAuthenticationHandler;
	protected FormAuthenticationHandler formAuthenticationHandler;
	protected Map<UUID, Nonce> nonces = new ConcurrentHashMap<UUID, Nonce>();
	protected int nonceValiditySeconds = 60 * 60 * 24;
	protected NonceProvider nonceProvider;
	protected AuthenticationService authenticationService;
	protected ExpiredNonceRemover expiredNonceRemover;
	protected List<Stoppable> shutdownHandlers = new CopyOnWriteArrayList<Stoppable>();
	protected ResourceTypeHelper resourceTypeHelper;
	protected WebDavResponseHandler webdavResponseHandler;
	// when wrapping a given response handler, this will be a reference to the outer most instance. or same as main response handler when not wrapping
	protected WebDavResponseHandler outerWebdavResponseHandler;
	protected ContentGenerator contentGenerator = new SimpleContentGenerator();
	protected CacheControlHelper cacheControlHelper = new DefaultCacheControlHelper();
	protected HandlerHelper handlerHelper;
	protected ArrayList<HttpExtension> protocols;
	protected ProtocolHandlers protocolHandlers;
	protected EntityTransport entityTransport;
	protected EventManager eventManager = new EventManagerImpl();
	protected PropertyAuthoriser propertyAuthoriser;
	protected List<PropertySource> propertySources;
	protected List<PropertySource> extraPropertySources;
	protected ETagGenerator eTagGenerator = new DefaultETagGenerator();
	protected Http11ResponseHandler http11ResponseHandler;
	protected ValueWriters valueWriters = new ValueWriters();
	protected PropFindXmlGenerator propFindXmlGenerator;
	protected List<Filter> filters;
	protected Filter defaultStandardFilter = new StandardFilter();
	protected UrlAdapter urlAdapter = new UrlAdapterImpl();
	protected QuotaDataAccessor quotaDataAccessor;
	protected PropPatchSetter propPatchSetter;
	protected boolean enableOptionsAuth = false;
	protected ResourceHandlerHelper resourceHandlerHelper;
	protected boolean initDone;
	protected boolean enableCompression = true;
	protected boolean enabledJson = true;
	protected boolean enableBasicAuth = true;
	protected boolean enableDigestAuth = true;
	protected boolean enableFormAuth = true;
	protected boolean enableCookieAuth = true;
	protected boolean enabledCkBrowser = false;
	protected String loginPage = "/login.html";
	protected List<String> loginPageExcludePaths;
	protected File rootDir = null;
	protected io.milton.http.SecurityManager securityManager;
	protected String fsContextPath;
	protected String fsRealm = "milton";
	protected Map<String, String> mapOfNameAndPasswords;
	protected String defaultUser = "user";
	protected String defaultPassword = "password";
	protected UserAgentHelper userAgentHelper;
	protected MultiNamespaceCustomPropertySource multiNamespaceCustomPropertySource;
	protected boolean multiNamespaceCustomPropertySourceEnabled = true;
	protected BeanPropertySource beanPropertySource;
	protected WebDavProtocol webDavProtocol;
	protected DisplayNameFormatter displayNameFormatter = new DefaultDisplayNameFormatter();
	protected boolean webdavEnabled = true;
	protected MatchHelper matchHelper;
	protected PartialGetHelper partialGetHelper;
	protected LoginResponseHandler loginResponseHandler;
	protected LoginResponseHandler.LoginPageTypeHandler loginPageTypeHandler = new LoginResponseHandler.ContentTypeLoginPageTypeHandler();
	protected boolean enableExpectContinue = false;
	protected String controllerPackagesToScan;
	protected String controllerClassNames;
	private Long maxAgeSeconds = 10l;
	private String fsHomeDir = null;
	private PropFindRequestFieldParser propFindRequestFieldParser;
	private PropFindPropertyBuilder propFindPropertyBuilder;

	protected io.milton.http.SecurityManager securityManager() {
		if (securityManager == null) {
			if (mapOfNameAndPasswords == null) {
				mapOfNameAndPasswords = new HashMap<String, String>();
				mapOfNameAndPasswords.put(defaultUser, defaultPassword);
				log.info("Configuring default user and password: " + defaultUser + "/" + defaultPassword + " for SimpleSecurityManager");
			}
			if (fsRealm == null) {
				fsRealm = "milton";
			}
			securityManager = new SimpleSecurityManager(fsRealm, mapOfNameAndPasswords);
		}
		log.info("Using securityManager: " + securityManager.getClass());
		return securityManager;
	}

	/**
	 * This method creates instances of required objects which have not been set
	 * on the builder.
	 *
	 * These are subsequently wired together immutably in HttpManager when
	 * buildHttpManager is called.
	 *
	 * You can call this before calling buildHttpManager if you would like to
	 * modify property values on the created objects before HttpManager is
	 * instantiated. Otherwise, you can call buildHttpManager directly and it
	 * will call init if it has not been called
	 *
	 */
	public final void init() {
		if (listeners != null) {
			for (InitListener l : listeners) {
				l.beforeInit(this);
			}
		}


		if (mainResourceFactory == null) {
			if (fsHomeDir == null) {
				fsHomeDir = System.getProperty("user.home");
			}
			rootDir = new File(fsHomeDir);
			if (!rootDir.exists() || !rootDir.isDirectory()) {
				throw new RuntimeException("Root directory is not valid: " + rootDir.getAbsolutePath());
			}
			FileSystemResourceFactory fsResourceFactory = new FileSystemResourceFactory(rootDir, securityManager(), fsContextPath);
			fsResourceFactory.setContentService(fileContentService);
			mainResourceFactory = fsResourceFactory;
			log.info("Using file system with root directory: " + rootDir.getAbsolutePath());
		}
		log.info("Using mainResourceFactory: " + mainResourceFactory.getClass());
		if (authenticationService == null) {
			if (authenticationHandlers == null) {
				authenticationHandlers = new ArrayList<AuthenticationHandler>();
				if (basicHandler == null) {
					if (enableBasicAuth) {
						basicHandler = new BasicAuthHandler();
					}
				}
				if (basicHandler != null) {
					authenticationHandlers.add(basicHandler);
				}
				if (digestHandler == null) {
					if (enableDigestAuth) {
						if (nonceProvider == null) {
							if (expiredNonceRemover == null) {
								expiredNonceRemover = new ExpiredNonceRemover(nonces, nonceValiditySeconds);
								showLog("expiredNonceRemover", expiredNonceRemover);
							}
							nonceProvider = new SimpleMemoryNonceProvider(nonceValiditySeconds, expiredNonceRemover, nonces);
							showLog("nonceProvider", nonceProvider);
						}
						digestHandler = new DigestAuthenticationHandler(nonceProvider);
					}
				}
				if (digestHandler != null) {
					authenticationHandlers.add(digestHandler);
				}
				if (formAuthenticationHandler == null) {
					if (enableFormAuth) {
						formAuthenticationHandler = new FormAuthenticationHandler();
					}
				}
				if (formAuthenticationHandler != null) {
					authenticationHandlers.add(formAuthenticationHandler);
				}
				if (extraAuthenticationHandlers != null && !extraAuthenticationHandlers.isEmpty()) {
					log.info("Adding extra auth handlers: " + extraAuthenticationHandlers.size());
					authenticationHandlers.addAll(extraAuthenticationHandlers);
				}
				if (cookieAuthenticationHandler == null) {
					if (enableCookieAuth) {
						if (cookieDelegateHandlers == null) {
							// Don't add digest!
							// why not???
							cookieDelegateHandlers = new ArrayList<AuthenticationHandler>();
							if (basicHandler != null) {
								cookieDelegateHandlers.add(basicHandler);
								authenticationHandlers.remove(basicHandler);
							}
							if (digestHandler != null) {
								cookieDelegateHandlers.add(digestHandler);
								authenticationHandlers.remove(digestHandler);
							}
							if (formAuthenticationHandler != null) {
								cookieDelegateHandlers.add(formAuthenticationHandler);
								authenticationHandlers.remove(formAuthenticationHandler);
							}
						}
						cookieAuthenticationHandler = new CookieAuthenticationHandler(cookieDelegateHandlers, mainResourceFactory);
						authenticationHandlers.add(cookieAuthenticationHandler);
					}
				}
			}
			authenticationService = new AuthenticationService(authenticationHandlers);
			showLog("authenticationService", authenticationService);
		}

		init(authenticationService);
	}

	private void init(AuthenticationService authenticationService) {
		// build a stack of resource type helpers
		if (resourceTypeHelper == null) {
			buildResourceTypeHelper();
		}
		if (propFindXmlGenerator == null) {
			propFindXmlGenerator = new PropFindXmlGenerator(valueWriters);
			showLog("propFindXmlGenerator", propFindXmlGenerator);
		}
		if (http11ResponseHandler == null) {
			DefaultHttp11ResponseHandler rh = createDefaultHttp11ResponseHandler(authenticationService);
			rh.setContentGenerator(contentGenerator);
			rh.setCacheControlHelper(cacheControlHelper);
			rh.setBuffering(buffering);
			http11ResponseHandler = rh;
			showLog("http11ResponseHandler", http11ResponseHandler);
		}

		if (webdavResponseHandler == null) {
			webdavResponseHandler = new DefaultWebDavResponseHandler(http11ResponseHandler, resourceTypeHelper, propFindXmlGenerator);
		}
		outerWebdavResponseHandler = webdavResponseHandler;
		if (enableCompression) {
			final CompressingResponseHandler compressingResponseHandler = new CompressingResponseHandler(webdavResponseHandler);
			compressingResponseHandler.setBuffering(buffering);
			outerWebdavResponseHandler = compressingResponseHandler;
			showLog("webdavResponseHandler", webdavResponseHandler);
		}
		if (enableFormAuth) {
			log.info("form authentication is enabled, so wrap response handler with " + LoginResponseHandler.class);
			if (loginResponseHandler == null) {
				loginResponseHandler = new LoginResponseHandler(outerWebdavResponseHandler, mainResourceFactory, loginPageTypeHandler);
				loginResponseHandler.setExcludePaths(loginPageExcludePaths);
				loginResponseHandler.setLoginPage(loginPage);
				outerWebdavResponseHandler = loginResponseHandler;
			}
		}

		init(authenticationService, outerWebdavResponseHandler, resourceTypeHelper);
		initAnnotatedResourceFactory();

		afterInit();
	}

	private void init(AuthenticationService authenticationService, WebDavResponseHandler webdavResponseHandler, ResourceTypeHelper resourceTypeHelper) {
		initDone = true;
		if (handlerHelper == null) {
			handlerHelper = new HandlerHelper(authenticationService);
			showLog("handlerHelper", handlerHelper);
		}
		if (!enableExpectContinue) {
			log.info("ExpectContinue support has been disabled");
		} else {
			log.info("ExpectContinue is enabled. This can cause problems on most servlet containers with clients such as CyberDuck");
		}
		handlerHelper.setEnableExpectContinue(enableExpectContinue);
		if (resourceHandlerHelper == null) {
			resourceHandlerHelper = new ResourceHandlerHelper(handlerHelper, urlAdapter, webdavResponseHandler, authenticationService);
			showLog("resourceHandlerHelper", resourceHandlerHelper);
		}
		buildProtocolHandlers(webdavResponseHandler, resourceTypeHelper);
		buildOuterResourceFactory();
		if (filters != null) {
			filters = new ArrayList<Filter>(filters);
		} else {
			filters = new ArrayList<Filter>();
		}
		filters.add(defaultStandardFilter);
	}

	public HttpManager buildHttpManager() {
		if (!initDone) {
			init();
		}
		if (listeners != null) {
			for (InitListener l : listeners) {
				l.afterInit(this);
			}
		}
		if (entityTransport == null) {
			entityTransport = new DefaultEntityTransport(userAgentHelper());
		}
		HttpManager httpManager = new HttpManager(outerResourceFactory, webdavResponseHandler, protocolHandlers, entityTransport, filters, eventManager, shutdownHandlers);
		if (listeners != null) {
			for (InitListener l : listeners) {
				l.afterBuild(this, httpManager);
			}
		}

		if (expiredNonceRemover != null) {
			shutdownHandlers.add(expiredNonceRemover);
			log.info("Starting " + expiredNonceRemover + " this will remove Digest nonces from memory when they expire");
			expiredNonceRemover.start();
		}

		return httpManager;
	}

	/**
	 * Overridable method called after init but before build
	 *
	 */
	protected void afterInit() {
	}

	protected PropertyAuthoriser initPropertyAuthoriser() {
		if (propertyAuthoriser == null) {
			propertyAuthoriser = new DefaultPropertyAuthoriser();
			if (beanPropertySource != null) {
				propertyAuthoriser = new BeanPropertyAuthoriser(beanPropertySource, propertyAuthoriser);
			}
		}
		return propertyAuthoriser;
	}

	protected List<PropertySource> initDefaultPropertySources(ResourceTypeHelper resourceTypeHelper) {
		List<PropertySource> list = new ArrayList<PropertySource>();
		if (multiNamespaceCustomPropertySource == null) {
			if (multiNamespaceCustomPropertySourceEnabled) {
				multiNamespaceCustomPropertySource = new MultiNamespaceCustomPropertySource();
			}
		}
		if (multiNamespaceCustomPropertySource != null) {
			list.add(multiNamespaceCustomPropertySource);
		}
		if (initBeanPropertySource() != null) {
			list.add(beanPropertySource);
		}
		return list;
	}

	protected BeanPropertySource initBeanPropertySource() {
		if (beanPropertySource == null) {
			beanPropertySource = new BeanPropertySource();
		}
		return beanPropertySource;
	}

	protected DefaultHttp11ResponseHandler createDefaultHttp11ResponseHandler(AuthenticationService authenticationService) {
		DefaultHttp11ResponseHandler rh = new DefaultHttp11ResponseHandler(authenticationService, eTagGenerator);
		return rh;
	}

	protected void buildResourceTypeHelper() {
		WebDavResourceTypeHelper webDavResourceTypeHelper = new WebDavResourceTypeHelper();
		resourceTypeHelper = webDavResourceTypeHelper;
		showLog("resourceTypeHelper", resourceTypeHelper);
	}

	protected void buildProtocolHandlers(WebDavResponseHandler webdavResponseHandler, ResourceTypeHelper resourceTypeHelper) {
		if (protocols == null) {
			protocols = new ArrayList<HttpExtension>();

			if (matchHelper == null) {
				matchHelper = new MatchHelper(eTagGenerator);
			}
			if (partialGetHelper == null) {
				partialGetHelper = new PartialGetHelper(webdavResponseHandler);
			}

			Http11Protocol http11Protocol = new Http11Protocol(webdavResponseHandler, handlerHelper, resourceHandlerHelper, enableOptionsAuth, matchHelper, partialGetHelper);
			protocols.add(http11Protocol);
			if (propertySources == null) {
				propertySources = initDefaultPropertySources(resourceTypeHelper);
				showLog("propertySources", propertySources);
			}
			if (extraPropertySources != null) {
				for (PropertySource ps : extraPropertySources) {
					log.info("Add extra property source: " + ps.getClass());
					propertySources.add(ps);
				}
			}

			initWebdavProtocol();
			if (webDavProtocol != null) {
				protocols.add(webDavProtocol);
			}
		}

		if (protocolHandlers == null) {
			protocolHandlers = new ProtocolHandlers(protocols);
		}
	}

	protected void initWebdavProtocol() {
		if (propPatchSetter == null) {
			propPatchSetter = new PropertySourcePatchSetter(propertySources);
		}
		if (propFindRequestFieldParser == null) {
			DefaultPropFindRequestFieldParser defaultFieldParse = new DefaultPropFindRequestFieldParser();
			this.propFindRequestFieldParser = new MsPropFindRequestFieldParser(defaultFieldParse); // use MS decorator for windows support				
		}
		if (webDavProtocol == null && webdavEnabled) {
			webDavProtocol = new WebDavProtocol(handlerHelper, resourceTypeHelper, webdavResponseHandler, propertySources, quotaDataAccessor, propPatchSetter, initPropertyAuthoriser(), eTagGenerator, urlAdapter, resourceHandlerHelper, userAgentHelper(), propFindRequestFieldParser(), propFindPropertyBuilder(), displayNameFormatter);
		}
	}

	protected PropFindRequestFieldParser propFindRequestFieldParser() {
		if (propFindRequestFieldParser == null) {
			DefaultPropFindRequestFieldParser defaultFieldParse = new DefaultPropFindRequestFieldParser();
			this.propFindRequestFieldParser = new MsPropFindRequestFieldParser(defaultFieldParse); // use MS decorator for windows support				
		}
		return propFindRequestFieldParser;
	}

	protected void buildOuterResourceFactory() {
		// wrap the real (ie main) resource factory to provide well-known support and ajax gateway
		if (outerResourceFactory == null) {
			outerResourceFactory = mainResourceFactory; // in case nothing else enabled
			if (enabledJson) {
				outerResourceFactory = buildJsonResourceFactory();
				log.info("Enabled json/ajax gatewayw with: " + outerResourceFactory.getClass());
			}
			if (enabledCkBrowser) {
				outerResourceFactory = new FckResourceFactory(outerResourceFactory);
				log.info("Enabled CK Editor support with: " + outerResourceFactory.getClass());
			}
		}
	}

	protected JsonResourceFactory buildJsonResourceFactory() {
		JsonPropFindHandler jsonPropFindHandler = new JsonPropFindHandler(propFindPropertyBuilder());
		JsonPropPatchHandler jsonPropPatchHandler = new JsonPropPatchHandler(propPatchSetter, initPropertyAuthoriser(), eventManager);
		return new JsonResourceFactory(outerResourceFactory, eventManager, jsonPropFindHandler, jsonPropPatchHandler);

	}

	public BUFFERING getBuffering() {
		return buffering;
	}

	public void setBuffering(BUFFERING buffering) {
		this.buffering = buffering;
	}

	public ResourceFactory getResourceFactory() {
		return mainResourceFactory;
	}

	public void setResourceFactory(ResourceFactory resourceFactory) {
		this.mainResourceFactory = resourceFactory;
	}

	public List<AuthenticationHandler> getAuthenticationHandlers() {
		return authenticationHandlers;
	}

	public void setAuthenticationHandlers(List<AuthenticationHandler> authenticationHandlers) {
		this.authenticationHandlers = authenticationHandlers;
	}

	/**
	 * You can add some extra auth handlers here, which will be added to the
	 * default auth handler structure such as basic, digest and cookie.
	 *
	 * @return
	 */
	public List<AuthenticationHandler> getExtraAuthenticationHandlers() {
		return extraAuthenticationHandlers;
	}

	public void setExtraAuthenticationHandlers(List<AuthenticationHandler> extraAuthenticationHandlers) {
		this.extraAuthenticationHandlers = extraAuthenticationHandlers;
	}

	/**
	 * Map holding nonce values issued in Digest authentication challenges
	 *
	 * @return
	 */
	public Map<UUID, Nonce> getNonces() {
		return nonces;
	}

	public void setNonces(Map<UUID, Nonce> nonces) {
		this.nonces = nonces;
	}

	/**
	 * This is your own resource factory, which provides access to your data
	 * repository. Not to be confused with outerResourceFactory which is
	 * normally used for milton specific things
	 *
	 * @return
	 */
	public ResourceFactory getMainResourceFactory() {
		return mainResourceFactory;
	}

	public void setMainResourceFactory(ResourceFactory mainResourceFactory) {
		this.mainResourceFactory = mainResourceFactory;
	}

	/**
	 * Usually set by milton, this will enhance the main resource factory with
	 * additional resources, such as .well-known support
	 *
	 * @return
	 */
	public ResourceFactory getOuterResourceFactory() {
		return outerResourceFactory;
	}

	public void setOuterResourceFactory(ResourceFactory outerResourceFactory) {
		this.outerResourceFactory = outerResourceFactory;
	}

	public int getNonceValiditySeconds() {
		return nonceValiditySeconds;
	}

	public void setNonceValiditySeconds(int nonceValiditySeconds) {
		this.nonceValiditySeconds = nonceValiditySeconds;
	}

	public NonceProvider getNonceProvider() {
		return nonceProvider;
	}

	public void setNonceProvider(NonceProvider nonceProvider) {
		this.nonceProvider = nonceProvider;
	}

	public AuthenticationService getAuthenticationService() {
		return authenticationService;
	}

	public void setAuthenticationService(AuthenticationService authenticationService) {
		this.authenticationService = authenticationService;
	}

	public ExpiredNonceRemover getExpiredNonceRemover() {
		return expiredNonceRemover;
	}

	public void setExpiredNonceRemover(ExpiredNonceRemover expiredNonceRemover) {
		this.expiredNonceRemover = expiredNonceRemover;
	}

	public List<Stoppable> getShutdownHandlers() {
		return shutdownHandlers;
	}

	public void setShutdownHandlers(List<Stoppable> shutdownHandlers) {
		this.shutdownHandlers = shutdownHandlers;
	}

	public ResourceTypeHelper getResourceTypeHelper() {
		return resourceTypeHelper;
	}

	public void setResourceTypeHelper(ResourceTypeHelper resourceTypeHelper) {
		this.resourceTypeHelper = resourceTypeHelper;
	}

	public WebDavResponseHandler getWebdavResponseHandler() {
		return webdavResponseHandler;
	}

	public void setWebdavResponseHandler(WebDavResponseHandler webdavResponseHandler) {
		this.webdavResponseHandler = webdavResponseHandler;
	}

	public HandlerHelper getHandlerHelper() {
		return handlerHelper;
	}

	public void setHandlerHelper(HandlerHelper handlerHelper) {
		this.handlerHelper = handlerHelper;
	}

	public ArrayList<HttpExtension> getProtocols() {
		return protocols;
	}

	public void setProtocols(ArrayList<HttpExtension> protocols) {
		this.protocols = protocols;
	}

	public ProtocolHandlers getProtocolHandlers() {
		return protocolHandlers;
	}

	public void setProtocolHandlers(ProtocolHandlers protocolHandlers) {
		this.protocolHandlers = protocolHandlers;
	}

	public EntityTransport getEntityTransport() {
		return entityTransport;
	}

	public void setEntityTransport(EntityTransport entityTransport) {
		this.entityTransport = entityTransport;
	}

	public EventManager getEventManager() {
		return eventManager;
	}

	public void setEventManager(EventManager eventManager) {
		this.eventManager = eventManager;
	}

	public PropertyAuthoriser getPropertyAuthoriser() {
		return propertyAuthoriser;
	}

	public void setPropertyAuthoriser(PropertyAuthoriser propertyAuthoriser) {
		this.propertyAuthoriser = propertyAuthoriser;
	}

	public List<PropertySource> getPropertySources() {
		return propertySources;
	}

	public void setPropertySources(List<PropertySource> propertySources) {
		this.propertySources = propertySources;
	}

	public ETagGenerator geteTagGenerator() {
		return eTagGenerator;
	}

	public void seteTagGenerator(ETagGenerator eTagGenerator) {
		this.eTagGenerator = eTagGenerator;
	}

	public Http11ResponseHandler getHttp11ResponseHandler() {
		return http11ResponseHandler;
	}

	public void setHttp11ResponseHandler(Http11ResponseHandler http11ResponseHandler) {
		this.http11ResponseHandler = http11ResponseHandler;
	}

	public ValueWriters getValueWriters() {
		return valueWriters;
	}

	public void setValueWriters(ValueWriters valueWriters) {
		this.valueWriters = valueWriters;
	}

	public PropFindXmlGenerator getPropFindXmlGenerator() {
		return propFindXmlGenerator;
	}

	public void setPropFindXmlGenerator(PropFindXmlGenerator propFindXmlGenerator) {
		this.propFindXmlGenerator = propFindXmlGenerator;
	}

	public List<Filter> getFilters() {
		return filters;
	}

	public void setFilters(List<Filter> filters) {
		this.filters = filters;
	}

	public Filter getDefaultStandardFilter() {
		return defaultStandardFilter;
	}

	public void setDefaultStandardFilter(Filter defaultStandardFilter) {
		this.defaultStandardFilter = defaultStandardFilter;
	}

	public UrlAdapter getUrlAdapter() {
		return urlAdapter;
	}

	public void setUrlAdapter(UrlAdapter urlAdapter) {
		this.urlAdapter = urlAdapter;
	}

	public QuotaDataAccessor getQuotaDataAccessor() {
		return quotaDataAccessor;
	}

	public void setQuotaDataAccessor(QuotaDataAccessor quotaDataAccessor) {
		this.quotaDataAccessor = quotaDataAccessor;
	}

	public PropPatchSetter getPropPatchSetter() {
		return propPatchSetter;
	}

	public void setPropPatchSetter(PropPatchSetter propPatchSetter) {
		this.propPatchSetter = propPatchSetter;
	}

	public boolean isInitDone() {
		return initDone;
	}

	public void setInitDone(boolean initDone) {
		this.initDone = initDone;
	}

	/**
	 * False by default, which means that OPTIONS requests will not trigger
	 * authentication. This is required for windows 7
	 *
	 */
	public boolean isEnableOptionsAuth() {
		return enableOptionsAuth;
	}

	public void setEnableOptionsAuth(boolean enableOptionsAuth) {
		this.enableOptionsAuth = enableOptionsAuth;
	}

	public boolean isEnableCompression() {
		return enableCompression;
	}

	public void setEnableCompression(boolean enableCompression) {
		this.enableCompression = enableCompression;
	}

	public boolean isEnabledJson() {
		return enabledJson;
	}

	public void setEnabledJson(boolean enabledJson) {
		this.enabledJson = enabledJson;
	}

	public List<PropertySource> getExtraPropertySources() {
		return extraPropertySources;
	}

	public void setExtraPropertySources(List<PropertySource> extraPropertySources) {
		this.extraPropertySources = extraPropertySources;
	}

	/**
	 *
	 * @param propertyName
	 * @param defaultedTo
	 */
	protected void showLog(String propertyName, Object defaultedTo) {
		log.info("set property: " + propertyName + " to: " + defaultedTo);
	}

	public boolean isEnableBasicAuth() {
		return enableBasicAuth;
	}

	public void setEnableBasicAuth(boolean enableBasicAuth) {
		this.enableBasicAuth = enableBasicAuth;
	}

	public boolean isEnableCookieAuth() {
		return enableCookieAuth;
	}

	public void setEnableCookieAuth(boolean enableCookieAuth) {
		this.enableCookieAuth = enableCookieAuth;
	}

	public boolean isEnableDigestAuth() {
		return enableDigestAuth;
	}

	public void setEnableDigestAuth(boolean enableDigestAuth) {
		this.enableDigestAuth = enableDigestAuth;
	}

	public boolean isEnableFormAuth() {
		return enableFormAuth;
	}

	public void setEnableFormAuth(boolean enableFormAuth) {
		this.enableFormAuth = enableFormAuth;
	}

	public BasicAuthHandler getBasicHandler() {
		return basicHandler;
	}

	public void setBasicHandler(BasicAuthHandler basicHandler) {
		this.basicHandler = basicHandler;
	}

	public CookieAuthenticationHandler getCookieAuthenticationHandler() {
		return cookieAuthenticationHandler;
	}

	public void setCookieAuthenticationHandler(CookieAuthenticationHandler cookieAuthenticationHandler) {
		this.cookieAuthenticationHandler = cookieAuthenticationHandler;
	}

	public List<AuthenticationHandler> getCookieDelegateHandlers() {
		return cookieDelegateHandlers;
	}

	public void setCookieDelegateHandlers(List<AuthenticationHandler> cookieDelegateHandlers) {
		this.cookieDelegateHandlers = cookieDelegateHandlers;
	}

	public DigestAuthenticationHandler getDigestHandler() {
		return digestHandler;
	}

	public void setDigestHandler(DigestAuthenticationHandler digestHandler) {
		this.digestHandler = digestHandler;
	}

	public FormAuthenticationHandler getFormAuthenticationHandler() {
		return formAuthenticationHandler;
	}

	public void setFormAuthenticationHandler(FormAuthenticationHandler formAuthenticationHandler) {
		this.formAuthenticationHandler = formAuthenticationHandler;
	}

	public String getLoginPage() {
		return loginPage;
	}

	public void setLoginPage(String loginPage) {
		this.loginPage = loginPage;
	}

	public List<String> getLoginPageExcludePaths() {
		return loginPageExcludePaths;
	}

	public void setLoginPageExcludePaths(List<String> loginPageExcludePaths) {
		this.loginPageExcludePaths = loginPageExcludePaths;
	}

	public ResourceHandlerHelper getResourceHandlerHelper() {
		return resourceHandlerHelper;
	}

	public void setResourceHandlerHelper(ResourceHandlerHelper resourceHandlerHelper) {
		this.resourceHandlerHelper = resourceHandlerHelper;
	}

	/**
	 * used by FileSystemResourceFactory when its created as default resource
	 * factory
	 *
	 * @return
	 */
	public File getRootDir() {
		return rootDir;
	}

	public void setRootDir(File rootDir) {
		this.rootDir = rootDir;
	}

	/**
	 * Mainly used when creating filesystem resourcfe factory, but can also be
	 * used by other resoruce factories that want to delegate security
	 * management
	 *
	 * @return
	 */
	public io.milton.http.SecurityManager getSecurityManager() {
		return securityManager;
	}

	public void setSecurityManager(io.milton.http.SecurityManager securityManager) {
		this.securityManager = securityManager;
	}

	/**
	 * Passed to FilesystemResourceFactory when its created
	 *
	 * @return
	 */
	public String getFsContextPath() {
		return fsContextPath;
	}

	public void setFsContextPath(String fsContextPath) {
		this.fsContextPath = fsContextPath;
	}

	public UserAgentHelper getUserAgentHelper() {
		return userAgentHelper;
	}

	public void setUserAgentHelper(UserAgentHelper userAgentHelper) {
		this.userAgentHelper = userAgentHelper;
	}

	public String getDefaultPassword() {
		return defaultPassword;
	}

	public void setDefaultPassword(String defaultPassword) {
		this.defaultPassword = defaultPassword;
	}

	public String getDefaultUser() {
		return defaultUser;
	}

	public void setDefaultUser(String defaultUser) {
		this.defaultUser = defaultUser;
	}

	public String getFsRealm() {
		return fsRealm;
	}

	public void setFsRealm(String fsRealm) {
		this.fsRealm = fsRealm;
	}

	public Map<String, String> getMapOfNameAndPasswords() {
		return mapOfNameAndPasswords;
	}

	public void setMapOfNameAndPasswords(Map<String, String> mapOfNameAndPasswords) {
		this.mapOfNameAndPasswords = mapOfNameAndPasswords;
	}

	public MultiNamespaceCustomPropertySource getMultiNamespaceCustomPropertySource() {
		return multiNamespaceCustomPropertySource;
	}

	public void setMultiNamespaceCustomPropertySource(MultiNamespaceCustomPropertySource multiNamespaceCustomPropertySource) {
		this.multiNamespaceCustomPropertySource = multiNamespaceCustomPropertySource;
	}

	public BeanPropertySource getBeanPropertySource() {
		return beanPropertySource;
	}

	public void setBeanPropertySource(BeanPropertySource beanPropertySource) {
		this.beanPropertySource = beanPropertySource;
	}

	/**
	 * Whether to enable support for CK Editor server browser support. If
	 * enabled this will inject the FckResourceFactory into your ResourceFactory
	 * stack.
	 *
	 * Note this will have no effect if outerResourceFactory is already set, as
	 * that is the top of the stack.
	 *
	 * @return
	 */
	public boolean isEnabledCkBrowser() {
		return enabledCkBrowser;
	}

	public void setEnabledCkBrowser(boolean enabledCkBrowser) {
		this.enabledCkBrowser = enabledCkBrowser;
	}

	public WebDavProtocol getWebDavProtocol() {
		return webDavProtocol;
	}

	public void setWebDavProtocol(WebDavProtocol webDavProtocol) {
		this.webDavProtocol = webDavProtocol;
	}

	public boolean isWebdavEnabled() {
		return webdavEnabled;
	}

	public void setWebdavEnabled(boolean webdavEnabled) {
		this.webdavEnabled = webdavEnabled;
	}

	public MatchHelper getMatchHelper() {
		return matchHelper;
	}

	public void setMatchHelper(MatchHelper matchHelper) {
		this.matchHelper = matchHelper;
	}

	public PartialGetHelper getPartialGetHelper() {
		return partialGetHelper;
	}

	public void setPartialGetHelper(PartialGetHelper partialGetHelper) {
		this.partialGetHelper = partialGetHelper;
	}

	public boolean isMultiNamespaceCustomPropertySourceEnabled() {
		return multiNamespaceCustomPropertySourceEnabled;
	}

	public void setMultiNamespaceCustomPropertySourceEnabled(boolean multiNamespaceCustomPropertySourceEnabled) {
		this.multiNamespaceCustomPropertySourceEnabled = multiNamespaceCustomPropertySourceEnabled;
	}

	public LoginPageTypeHandler getLoginPageTypeHandler() {
		return loginPageTypeHandler;
	}

	public void setLoginPageTypeHandler(LoginPageTypeHandler loginPageTypeHandler) {
		this.loginPageTypeHandler = loginPageTypeHandler;
	}

	public LoginResponseHandler getLoginResponseHandler() {
		return loginResponseHandler;
	}

	public void setLoginResponseHandler(LoginResponseHandler loginResponseHandler) {
		this.loginResponseHandler = loginResponseHandler;
	}

	public List<InitListener> getListeners() {
		return listeners;
	}

	public void setListeners(List<InitListener> listeners) {
		this.listeners = listeners;
	}

	public FileContentService getFileContentService() {
		return fileContentService;
	}

	public void setFileContentService(FileContentService fileContentService) {
		this.fileContentService = fileContentService;
	}

	public CacheControlHelper getCacheControlHelper() {
		return cacheControlHelper;
	}

	public void setCacheControlHelper(CacheControlHelper cacheControlHelper) {
		this.cacheControlHelper = cacheControlHelper;
	}

	public ContentGenerator getContentGenerator() {
		return contentGenerator;
	}

	public void setContentGenerator(ContentGenerator contentGenerator) {
		this.contentGenerator = contentGenerator;
	}

	public void setEnableExpectContinue(boolean enableExpectContinue) {
		this.enableExpectContinue = enableExpectContinue;
	}

	/**
	 * If true milton will response to Expect: Continue requests. This can
	 * cause a problem on some web servers
	 * 
	 * @return 
	 */
	public boolean isEnableExpectContinue() {
		return enableExpectContinue;
	}

	public WebDavResponseHandler getOuterWebdavResponseHandler() {
		return outerWebdavResponseHandler;
	}

	/**
	 * If not null, is expected to be a comma seperated list of package names. These
	 * will be scanned for classes which contain classes annotated with ResourceController,
	 * and those found will be added to the controllers list
	 * 
	 * @return 
	 */
	public String getControllerPackagesToScan() {
		return controllerPackagesToScan;
	}

	public void setControllerPackagesToScan(String controllerPackagesToScan) {
		this.controllerPackagesToScan = controllerPackagesToScan;
	}

	/**
	 * As an alternative to package scanning via the controllerPackagesToScan property,
	 * set this property to a comma seperated list of class names. These will be
	 * loaded and checked for the ResourceController annotation, and if present,
	 * will be added to the controllers list
	 * 
	 * @return 
	 */
	public String getControllerClassNames() {
		return controllerClassNames;
	}

	public void setControllerClassNames(String controlleClassNames) {
		this.controllerClassNames = controlleClassNames;
	}

	/**
	 * Default max-age to use for certain resource types which can use a default value
	 * 
	 * @return 
	 */
	public Long getMaxAgeSeconds() {
		return maxAgeSeconds;
	}

	public void setMaxAgeSeconds(Long maxAgeSeconds) {
		this.maxAgeSeconds = maxAgeSeconds;
	}

	public DisplayNameFormatter getDisplayNameFormatter() {
		return displayNameFormatter;
	}

	public void setDisplayNameFormatter(DisplayNameFormatter displayNameFormatter) {
		this.displayNameFormatter = displayNameFormatter;
	}

	/**
	 * Set this if you're using the FileSystemResourceFactory and you want to
	 * explicitly set a home directory. If left null milton will use the
	 * user.home System property
	 *
	 * @return
	 */
	public String getFsHomeDir() {
		return fsHomeDir;
	}

	public void setFsHomeDir(String fsHomeDir) {
		this.fsHomeDir = fsHomeDir;
	}

	public PropFindPropertyBuilder getPropFindPropertyBuilder() {
		return propFindPropertyBuilder;
	}

	public void setPropFindPropertyBuilder(PropFindPropertyBuilder propFindPropertyBuilder) {
		this.propFindPropertyBuilder = propFindPropertyBuilder;
	}

	public PropFindRequestFieldParser getPropFindRequestFieldParser() {
		return propFindRequestFieldParser;
	}

	public void setPropFindRequestFieldParser(PropFindRequestFieldParser propFindRequestFieldParser) {
		this.propFindRequestFieldParser = propFindRequestFieldParser;
	}

	private void initAnnotatedResourceFactory() {
		try {
			if (getMainResourceFactory() instanceof AnnotationResourceFactory) {
				AnnotationResourceFactory arf = (AnnotationResourceFactory) getMainResourceFactory();
				if (arf.getControllers() == null) {
					List controllers = new ArrayList();
					if (controllerPackagesToScan != null) {
						log.info("Scan for controller classes: " + controllerPackagesToScan);
						for (String packageName : controllerPackagesToScan.split(",")) {
							packageName = packageName.trim();
							log.info("init annotations controllers from package: " + packageName);
							List<Class> classes = ReflectionUtils.getClassNamesFromPackage(packageName);
							for (Class c : classes) {
								Annotation a = c.getAnnotation(ResourceController.class);
								if (a != null) {
									Object controller = c.newInstance();
									controllers.add(controller);
								}
							}
						}
					}
					if (controllerClassNames != null) {
						log.info("Initialise controller classes: " + controllerClassNames);
						for (String className : controllerClassNames.split(",")) {
							className = className.trim();
							log.info("init annotation controller: " + className);
							Class c = ReflectionUtils.loadClass(className);
							Annotation a = c.getAnnotation(ResourceController.class);
							if (a != null) {
								Object controller = c.newInstance();
								controllers.add(controller);
							} else {
								log.warn("No " + ResourceController.class + " annotation on class: " + c.getCanonicalName() + " provided in controlleClassNames");
							}
						}

					}
					if( controllers.isEmpty() ) {
						log.warn("No controllers found in controllerClassNames=" + controllerClassNames + "  or controllerPackagesToScan=" + controllerPackagesToScan);
					}
					arf.setControllers(controllers);
				}

				if (arf.getMaxAgeSeconds() == null) {
					arf.setMaxAgeSeconds(maxAgeSeconds);
				}
				if (arf.getSecurityManager() == null) {
					// init the default, statically configured sm
					arf.setSecurityManager(securityManager());
				}
				setDisplayNameFormatter(arf.new AnnotationsDisplayNameFormatter(getDisplayNameFormatter()));
			}
		} catch (InstantiationException e) {
			throw new RuntimeException("Exception initialising AnnotationResourceFactory", e);
		} catch (IllegalAccessException e) {
			throw new RuntimeException("Exception initialising AnnotationResourceFactory", e);
		} catch (IOException e) {
			throw new RuntimeException("Exception initialising AnnotationResourceFactory", e);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException("Exception initialising AnnotationResourceFactory", e);
		}
	}

	protected UserAgentHelper userAgentHelper() {
		if (userAgentHelper == null) {
			userAgentHelper = new DefaultUserAgentHelper();
		}
		return userAgentHelper;
	}

	protected PropFindPropertyBuilder propFindPropertyBuilder() {
		if (propFindPropertyBuilder == null) {
			if (propertySources == null) {
				throw new RuntimeException("propertySources has not been initialised yet");
			}
			propFindPropertyBuilder = new DefaultPropFindPropertyBuilder(propertySources);
		}
		return propFindPropertyBuilder;
	}
}

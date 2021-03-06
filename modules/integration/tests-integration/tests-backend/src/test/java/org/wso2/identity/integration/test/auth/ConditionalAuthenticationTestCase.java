/*
 *  Copyright (c) 2018 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.identity.integration.test.auth;

import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.ConfigurationContextFactory;
import org.apache.commons.lang.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.carbon.automation.engine.context.AutomationContext;
import org.wso2.carbon.automation.engine.context.TestUserMode;
import org.wso2.carbon.automation.extensions.servers.carbonserver.MultipleServersManager;
import org.wso2.carbon.identity.application.common.model.idp.xsd.FederatedAuthenticatorConfig;
import org.wso2.carbon.identity.application.common.model.idp.xsd.IdentityProvider;
import org.wso2.carbon.identity.application.common.model.script.xsd.AuthenticationScriptConfig;
import org.wso2.carbon.identity.application.common.model.xsd.AuthenticationStep;
import org.wso2.carbon.identity.application.common.model.xsd.InboundAuthenticationConfig;
import org.wso2.carbon.identity.application.common.model.xsd.InboundAuthenticationRequestConfig;
import org.wso2.carbon.identity.application.common.model.xsd.LocalAndOutboundAuthenticationConfig;
import org.wso2.carbon.identity.application.common.model.xsd.LocalAuthenticatorConfig;
import org.wso2.carbon.identity.application.common.model.xsd.Property;
import org.wso2.carbon.identity.application.common.model.xsd.ServiceProvider;
import org.wso2.carbon.identity.oauth.stub.OAuthAdminServiceIdentityOAuthAdminException;
import org.wso2.carbon.identity.oauth.stub.dto.OAuthConsumerAppDTO;
import org.wso2.carbon.identity.sso.saml.stub.types.SAMLSSOServiceProviderDTO;
import org.wso2.carbon.integration.common.admin.client.AuthenticatorClient;
import org.wso2.identity.integration.common.clients.Idp.IdentityProviderMgtServiceClient;
import org.wso2.identity.integration.common.clients.application.mgt.ApplicationManagementServiceClient;
import org.wso2.identity.integration.common.clients.oauth.OauthAdminClient;
import org.wso2.identity.integration.common.clients.sso.saml.SAMLSSOConfigServiceClient;
import org.wso2.identity.integration.common.utils.CarbonTestServerManager;
import org.wso2.identity.integration.test.oauth2.OAuth2ServiceAbstractIntegrationTest;
import org.wso2.identity.integration.test.utils.CommonConstants;
import org.wso2.identity.integration.test.utils.DataExtractUtil;
import org.wso2.identity.integration.test.utils.IdentityConstants;
import org.wso2.identity.integration.test.utils.OAuth2Constant;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertTrue;
import static org.wso2.identity.integration.test.utils.CommonConstants.IS_DEFAULT_HTTPS_PORT;
import static org.wso2.identity.integration.test.utils.OAuth2Constant.CALLBACK_URL;
import static org.wso2.identity.integration.test.utils.OAuth2Constant.COMMON_AUTH_URL;

/**
 * Test class to test the conditional authentication support using Javascript feature.
 */
public class ConditionalAuthenticationTestCase extends OAuth2ServiceAbstractIntegrationTest {

    private static final String IDENTITY_PROVIDER_ALIAS =
            "https://localhost:" + IS_DEFAULT_HTTPS_PORT + "/oauth2/token/";
    private static final String SECONDARY_IS_SAMLSSO_URL = "https://localhost:9854/samlsso";
    private static final int PORT_OFFSET_1 = 1;
    private static final String SAML_NAME_ID_FORMAT = "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress";
    private static final String PRIMARY_IS_APPLICATION_NAME = "testOauthApp";
    private static final String SECONDARY_IS_APPLICATION_NAME = "testSAMLApp";
    private static final String IDP_NAME = "secondaryIS";

    private AuthenticatorClient logManger;
    private OauthAdminClient oauthAdminClient;
    private ApplicationManagementServiceClient applicationManagementServiceClient, applicationManagementServiceClient2;
    private IdentityProviderMgtServiceClient identityProviderMgtServiceClient;
    private MultipleServersManager manager;
    private SAMLSSOConfigServiceClient samlSSOConfigServiceClient;
    private DefaultHttpClient client;
    private ServiceProvider serviceProvider;
    private LocalAndOutboundAuthenticationConfig outboundAuthConfig;
    private HttpResponse response;

    private String consumerKey;
    private String consumerSecret;
    private String script;
    private String initialCarbonHome;

    public static final String ENABLE_CONDITIONAL_AUTHENTICATION_FLAG = "enableConditionalAuthenticationFeature";
    private boolean isEnableConditionalAuthenticationFeature =
            System.getProperty(ENABLE_CONDITIONAL_AUTHENTICATION_FLAG) != null;

    @BeforeClass(alwaysRun = true)
    public void testInit() throws Exception {

        super.init();
        initialCarbonHome = System.getProperty("carbon.home");
        logManger = new AuthenticatorClient(backendURL);
        String cookie = this.logManger.login(isServer.getSuperTenant().getTenantAdmin().getUserName(),
                isServer.getSuperTenant().getTenantAdmin().getPassword(),
                isServer.getInstance().getHosts().get("default"));
        oauthAdminClient = new OauthAdminClient(backendURL, cookie);
        ConfigurationContext configContext = ConfigurationContextFactory
                .createConfigurationContextFromFileSystem(null, null);
        applicationManagementServiceClient = new ApplicationManagementServiceClient(sessionCookie, backendURL,
                configContext);
        identityProviderMgtServiceClient = new IdentityProviderMgtServiceClient(sessionCookie, backendURL);
        manager = new MultipleServersManager();

        client = new DefaultHttpClient();

        startSecondaryIS();
        readConditionalAuthScript("ConditionalAuthenticationTestCase.js");

        createSAMLAppInSecondaryIS();
        createServiceProviderInSecondaryIS();

        // Create federated IDP in primary IS.
        createIDPInPrimaryIS();
        createOauthAppInPrimaryIS();
        // Create service provider in primary IS with conditional authentication script enabled.
        createServiceProviderInPrimaryIS();
    }

    @AfterClass(alwaysRun = true)
    public void atEnd() throws Exception {

        oauthAdminClient.removeOAuthApplicationData(consumerKey);
        samlSSOConfigServiceClient.removeServiceProvider(SECONDARY_IS_APPLICATION_NAME);
        applicationManagementServiceClient.deleteApplication(PRIMARY_IS_APPLICATION_NAME);
        applicationManagementServiceClient2.deleteApplication(SECONDARY_IS_APPLICATION_NAME);
        identityProviderMgtServiceClient.deleteIdP(IDP_NAME);
        client.getConnectionManager().shutdown();

        this.logManger.logOut();
        logManger = null;
        manager.stopAllServers();
        //Restore carbon.home system property to initial value
        System.setProperty("carbon.home", initialCarbonHome);
    }

    @Test(groups = "wso2.is", description = "Check conditional authentication flow.")
    public void testConditionalAuthentication() throws Exception {

        if( !isEnableConditionalAuthenticationFeature) {
            return;
        }
        LoginToPrimaryIS();
        /* Here if the client is redirected to the secondary IS, it indicates that the conditional authentication steps
         has been successfully completed. */
        assertTrue(response.getFirstHeader("location").getValue().contains(SECONDARY_IS_SAMLSSO_URL),
                "Failed to follow the conditional authentication steps.");
        EntityUtils.consume(response.getEntity());
    }

    @Test(groups = "wso2.is", description = "Check conditional authentication flow based on HTTP Cookie.")
    public void testConditionalAuthenticationUsingHTTPCookie() throws Exception {

        if( !isEnableConditionalAuthenticationFeature) {
            return;
        }

        // Update authentication script to handle authentication based on HTTP context.
        updateAuthScript("ConditionalAuthenticationHTTPCookieTestCase.js");
        LoginToPrimaryIS();

        /* Here if the response headers contains the custom HTTP cookie we set from the authentication script, it
        indicates that the conditional authentication steps has been successfully completed. */
        boolean hasTestCookie = false;
        Header[] headers = response.getHeaders("Set-Cookie");
        if (headers != null) {
            for (Header header : headers) {
                String headerValue = header.getValue();
                if (headerValue.contains("testcookie")) {
                    hasTestCookie = true;
                }
            }
        }
        assertTrue(hasTestCookie, "Failed to follow the conditional authentication steps. HTTP Cookie : "
                + "testcookie was not found in the response.");
        EntityUtils.consume(response.getEntity());
    }

    @Test(groups = "wso2.is", description = "Check conditional authentication flow with claim assignment.")
    public void testConditionalAuthenticationClaimAssignment() throws Exception {

        if( !isEnableConditionalAuthenticationFeature) {
            return;
        }
        // Update authentication script to handle authentication based on HTTP context.
        updateAuthScript("ConditionalAuthenticationClaimAssignTestCase.js");
        LoginToPrimaryIS();

        EntityUtils.consume(response.getEntity());
    }

    private void LoginToPrimaryIS() throws Exception {

        List<NameValuePair> urlParameters = new ArrayList<>();
        urlParameters.add(new BasicNameValuePair("response_type", OAuth2Constant.OAUTH2_GRANT_TYPE_CODE));
        urlParameters.add(new BasicNameValuePair("scope", "openid"));
        urlParameters.add(new BasicNameValuePair("redirect_uri", CALLBACK_URL));
        urlParameters.add(new BasicNameValuePair("client_id", consumerKey));
        urlParameters.add(new BasicNameValuePair("acr_values", "acr1"));
        urlParameters.add(new BasicNameValuePair("accessEndpoint", OAuth2Constant.ACCESS_TOKEN_ENDPOINT));
        urlParameters.add(new BasicNameValuePair("authorize", OAuth2Constant.AUTHORIZE_PARAM));
        response = sendPostRequestWithParameters(client, urlParameters, OAuth2Constant.APPROVAL_URL);
        Assert.assertNotNull(response, "Authorization request failed. Authorized response is null.");
        Header locationHeader = response.getFirstHeader(OAuth2Constant.HTTP_RESPONSE_HEADER_LOCATION);
        Assert.assertNotNull(locationHeader, "Authorized response header is null.");
        EntityUtils.consume(response.getEntity());

        response = sendGetRequest(client, locationHeader.getValue());
        Assert.assertNotNull(response, "Authorization request failed for " + PRIMARY_IS_APPLICATION_NAME + ". "
                + "Authorized user response is null.");

        Map<String, Integer> keyPositionMap = new HashMap<>(1);
        keyPositionMap.put("name=\"sessionDataKey\"", 1);
        List<DataExtractUtil.KeyValue> keyValues = DataExtractUtil.extractDataFromResponse(response, keyPositionMap);
        Assert.assertNotNull(keyValues, "sessionDataKey key value is null for " + PRIMARY_IS_APPLICATION_NAME);

        String sessionDataKey = keyValues.get(0).getValue();
        Assert.assertNotNull(sessionDataKey, "Invalid sessionDataKey for " + PRIMARY_IS_APPLICATION_NAME);
        EntityUtils.consume(response.getEntity());

        response = sendLoginPost(client, sessionDataKey);
    }

    private void createServiceProviderInPrimaryIS() throws Exception {

        OAuthConsumerAppDTO[] appDtos = oauthAdminClient.getAllOAuthApplicationData();

        for (OAuthConsumerAppDTO appDto : appDtos) {
            if (appDto.getApplicationName().equals(PRIMARY_IS_APPLICATION_NAME)) {
                consumerKey = appDto.getOauthConsumerKey();
                consumerSecret = appDto.getOauthConsumerSecret();
            }
        }

        serviceProvider = new ServiceProvider();
        serviceProvider.setApplicationName(PRIMARY_IS_APPLICATION_NAME);
        serviceProvider.setDescription("This is a test Service Provider for conditional authentication flow test.");
        applicationManagementServiceClient.createApplication(serviceProvider);
        serviceProvider = applicationManagementServiceClient.getApplication(PRIMARY_IS_APPLICATION_NAME);

        InboundAuthenticationRequestConfig requestConfig = new InboundAuthenticationRequestConfig();
        requestConfig.setInboundAuthKey(consumerKey);
        requestConfig.setInboundAuthType("oauth2");
        if (StringUtils.isNotBlank(consumerSecret)) {
            Property property = new Property();
            property.setName("oauthConsumerSecret");
            property.setValue(consumerSecret);
            Property[] properties = { property };
            requestConfig.setProperties(properties);
        }

        InboundAuthenticationConfig inboundAuthenticationConfig = new InboundAuthenticationConfig();
        inboundAuthenticationConfig
                .setInboundAuthenticationRequestConfigs(new InboundAuthenticationRequestConfig[] { requestConfig });
        serviceProvider.setInboundAuthenticationConfig(inboundAuthenticationConfig);

        outboundAuthConfig = createLocalAndOutboundAuthenticationConfig();
        outboundAuthConfig.setEnableAuthorization(true);
        AuthenticationScriptConfig config = new AuthenticationScriptConfig();
        config.setContent(script);
        config.setEnabled(true);
        outboundAuthConfig.setAuthenticationScriptConfig(config);
        serviceProvider.setLocalAndOutBoundAuthenticationConfig(outboundAuthConfig);
        applicationManagementServiceClient.updateApplicationData(serviceProvider);
    }

    private void createOauthAppInPrimaryIS() throws RemoteException, OAuthAdminServiceIdentityOAuthAdminException {

        OAuthConsumerAppDTO appDTO = new OAuthConsumerAppDTO();
        appDTO.setCallbackUrl(CALLBACK_URL);
        appDTO.setGrantTypes("authorization_code implicit password client_credentials refresh_token "
                + "urn:ietf:params:oauth:grant-type:saml2-bearer iwa:ntlm");
        appDTO.setOAuthVersion(OAuth2Constant.OAUTH_VERSION_2);
        appDTO.setApplicationName(PRIMARY_IS_APPLICATION_NAME);
        oauthAdminClient.registerOAuthApplicationData(appDTO);
    }

    /**
     * Create the AdvancedAuthenticator with Multi steps.
     * Use any attributes needed if needed to do multiple tests with different advanced authenticators.
     *
     * @throws Exception
     */
    private LocalAndOutboundAuthenticationConfig createLocalAndOutboundAuthenticationConfig() throws Exception {

        LocalAndOutboundAuthenticationConfig localAndOutboundAuthenticationConfig = new LocalAndOutboundAuthenticationConfig();
        localAndOutboundAuthenticationConfig.setAuthenticationType("flow");
        AuthenticationStep authenticationStep1 = new AuthenticationStep();
        authenticationStep1.setStepOrder(1);
        LocalAuthenticatorConfig localConfig = new LocalAuthenticatorConfig();
        localConfig.setName(CommonConstants.BASIC_AUTHENTICATOR);
        localConfig.setDisplayName("basicauth");
        localConfig.setEnabled(true);
        authenticationStep1.setLocalAuthenticatorConfigs(new LocalAuthenticatorConfig[] { localConfig });
        authenticationStep1.setSubjectStep(true);
        authenticationStep1.setAttributeStep(true);
        localAndOutboundAuthenticationConfig.addAuthenticationSteps(authenticationStep1);

        AuthenticationStep authenticationStep2 = new AuthenticationStep();
        authenticationStep2.setStepOrder(2);
        authenticationStep2.setFederatedIdentityProviders(
                new org.wso2.carbon.identity.application.common.model.xsd.IdentityProvider[] {
                        getFederatedSAMLSSOIDP() });
        localAndOutboundAuthenticationConfig.addAuthenticationSteps(authenticationStep2);

        return localAndOutboundAuthenticationConfig;
    }

    /**
     * Create IDP for SAMLSSO Authenticator.
     *
     * @throws Exception
     */
    private void createIDPInPrimaryIS() throws Exception {

        IdentityProvider identityProvider = new IdentityProvider();
        identityProvider.setIdentityProviderName(IDP_NAME);
        identityProvider.setAlias(IDENTITY_PROVIDER_ALIAS);
        identityProvider.setEnable(true);

        FederatedAuthenticatorConfig saml2SSOAuthnConfig = new FederatedAuthenticatorConfig();
        saml2SSOAuthnConfig.setName("SAMLSSOAuthenticator");
        saml2SSOAuthnConfig.setDisplayName("samlsso");
        saml2SSOAuthnConfig.setEnabled(true);
        saml2SSOAuthnConfig.setProperties(getSAML2SSOAuthnConfigProperties());
        identityProvider.setDefaultAuthenticatorConfig(saml2SSOAuthnConfig);
        identityProvider.setFederatedAuthenticatorConfigs(new FederatedAuthenticatorConfig[] { saml2SSOAuthnConfig });

        identityProviderMgtServiceClient.addIdP(identityProvider);
    }

    /**
     * Get SAMLSSO configuration properties.
     *
     * @return
     */
    private org.wso2.carbon.identity.application.common.model.idp.xsd.Property[] getSAML2SSOAuthnConfigProperties() {

        org.wso2.carbon.identity.application.common.model.idp.xsd.Property[] properties = new org.wso2.carbon.identity.application.common.model.idp.xsd.Property[13];
        org.wso2.carbon.identity.application.common.model.idp.xsd.Property property = new org.wso2.carbon.identity.application.common.model.idp.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.IDP_ENTITY_ID);
        property.setValue(IDP_NAME);
        properties[0] = property;

        property = new org.wso2.carbon.identity.application.common.model.idp.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.SP_ENTITY_ID);
        property.setValue(SECONDARY_IS_APPLICATION_NAME);
        properties[1] = property;

        property = new org.wso2.carbon.identity.application.common.model.idp.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.SSO_URL);
        property.setValue(SECONDARY_IS_SAMLSSO_URL);
        properties[2] = property;

        property = new org.wso2.carbon.identity.application.common.model.idp.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.IS_AUTHN_REQ_SIGNED);
        property.setValue("false");
        properties[3] = property;

        property = new org.wso2.carbon.identity.application.common.model.idp.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.IS_LOGOUT_ENABLED);
        property.setValue("true");
        properties[4] = property;

        property = new org.wso2.carbon.identity.application.common.model.idp.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.LOGOUT_REQ_URL);
        properties[5] = property;

        property = new org.wso2.carbon.identity.application.common.model.idp.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.IS_LOGOUT_REQ_SIGNED);
        property.setValue("false");
        properties[6] = property;

        property = new org.wso2.carbon.identity.application.common.model.idp.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.IS_AUTHN_RESP_SIGNED);
        property.setValue("false");
        properties[7] = property;

        property = new org.wso2.carbon.identity.application.common.model.idp.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.IS_USER_ID_IN_CLAIMS);
        property.setValue("false");
        properties[8] = property;

        property = new org.wso2.carbon.identity.application.common.model.idp.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.IS_ENABLE_ASSERTION_ENCRYPTION);
        property.setValue("false");
        properties[9] = property;

        property = new org.wso2.carbon.identity.application.common.model.idp.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.IS_ENABLE_ASSERTION_SIGNING);
        property.setValue("false");
        properties[10] = property;

        property = new org.wso2.carbon.identity.application.common.model.idp.xsd.Property();
        property.setName("commonAuthQueryParams");
        properties[11] = property;

        property = new org.wso2.carbon.identity.application.common.model.idp.xsd.Property();
        property.setName("AttributeConsumingServiceIndex");
        properties[12] = property;

        return properties;
    }

    /**
     * Get SAML SSO configuration properties for XSD.
     *
     * @return
     */
    private org.wso2.carbon.identity.application.common.model.xsd.Property[] getSAMLSSOConfigurationPropertiesForXSD() {

        org.wso2.carbon.identity.application.common.model.xsd.Property[] properties = new org.wso2.carbon.identity.application.common.model.xsd.Property[13];

        org.wso2.carbon.identity.application.common.model.xsd.Property property = new org.wso2.carbon.identity.application.common.model.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.IDP_ENTITY_ID);
        property.setValue(IDP_NAME);
        properties[0] = property;

        property = new org.wso2.carbon.identity.application.common.model.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.SP_ENTITY_ID);
        property.setValue(SECONDARY_IS_APPLICATION_NAME);
        properties[1] = property;

        property = new org.wso2.carbon.identity.application.common.model.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.SSO_URL);
        property.setValue(SECONDARY_IS_SAMLSSO_URL);
        properties[2] = property;

        property = new org.wso2.carbon.identity.application.common.model.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.IS_AUTHN_REQ_SIGNED);
        property.setValue("false");
        properties[3] = property;

        property = new org.wso2.carbon.identity.application.common.model.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.IS_LOGOUT_ENABLED);
        property.setValue("true");
        properties[4] = property;

        property = new org.wso2.carbon.identity.application.common.model.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.LOGOUT_REQ_URL);
        properties[5] = property;

        property = new org.wso2.carbon.identity.application.common.model.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.IS_LOGOUT_REQ_SIGNED);
        property.setValue("false");
        properties[6] = property;

        property = new org.wso2.carbon.identity.application.common.model.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.IS_AUTHN_RESP_SIGNED);
        property.setValue("false");
        properties[7] = property;

        property = new org.wso2.carbon.identity.application.common.model.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.IS_USER_ID_IN_CLAIMS);
        property.setValue("false");
        properties[8] = property;

        property = new org.wso2.carbon.identity.application.common.model.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.IS_ENABLE_ASSERTION_ENCRYPTION);
        property.setValue("false");
        properties[9] = property;

        property = new org.wso2.carbon.identity.application.common.model.xsd.Property();
        property.setName(IdentityConstants.Authenticator.SAML2SSO.IS_ENABLE_ASSERTION_SIGNING);
        property.setValue("false");
        properties[10] = property;

        property = new org.wso2.carbon.identity.application.common.model.xsd.Property();
        property.setName("commonAuthQueryParams");
        properties[11] = property;

        property = new org.wso2.carbon.identity.application.common.model.xsd.Property();
        property.setName("AttributeConsumingServiceIndex");
        properties[12] = property;

        return properties;
    }

    private org.wso2.carbon.identity.application.common.model.xsd.IdentityProvider getFederatedSAMLSSOIDP() {

        org.wso2.carbon.identity.application.common.model.xsd.IdentityProvider identityProvider = new org.wso2.carbon.identity.application.common.model.xsd.IdentityProvider();
        identityProvider.setIdentityProviderName(IDP_NAME);
        identityProvider.setAlias(IDENTITY_PROVIDER_ALIAS);
        identityProvider.setEnable(true);

        org.wso2.carbon.identity.application.common.model.xsd.FederatedAuthenticatorConfig federatedAuthenticatorConfig = new org.wso2.carbon.identity.application.common.model.xsd.FederatedAuthenticatorConfig();
        federatedAuthenticatorConfig.setProperties(getSAMLSSOConfigurationPropertiesForXSD());
        federatedAuthenticatorConfig.setName("SAMLSSOAuthenticator");
        federatedAuthenticatorConfig.setDisplayName("samlsso");
        federatedAuthenticatorConfig.setEnabled(true);
        identityProvider.setDefaultAuthenticatorConfig(federatedAuthenticatorConfig);
        identityProvider.setFederatedAuthenticatorConfigs(
                new org.wso2.carbon.identity.application.common.model.xsd.FederatedAuthenticatorConfig[] {
                        federatedAuthenticatorConfig });
        return identityProvider;
    }

    private void startSecondaryIS() throws Exception {

        Map<String, String> startupParameters = new HashMap<>();
        startupParameters.put("-DportOffset", String.valueOf(PORT_OFFSET_1 + CommonConstants.IS_DEFAULT_OFFSET));
        AutomationContext context = new AutomationContext("IDENTITY", "identity002", TestUserMode.SUPER_TENANT_ADMIN);

        startCarbonServer(context, startupParameters);
        String serviceUrl = (context.getContextUrls().getSecureServiceUrl())
                .replace("9853", String.valueOf(IS_DEFAULT_HTTPS_PORT + PORT_OFFSET_1)) + "/";

        AuthenticatorClient authenticatorClient = new AuthenticatorClient(serviceUrl);

        sessionCookie = authenticatorClient.login(context.getSuperTenant().getTenantAdmin().getUserName(),
                context.getSuperTenant().getTenantAdmin().getPassword(),
                context.getDefaultInstance().getHosts().get("default"));

        if (sessionCookie != null) {
            ConfigurationContext configContext = ConfigurationContextFactory
                    .createConfigurationContextFromFileSystem(null, null);
            applicationManagementServiceClient2 = new ApplicationManagementServiceClient(sessionCookie, serviceUrl,
                    configContext);
            samlSSOConfigServiceClient = new SAMLSSOConfigServiceClient(serviceUrl, sessionCookie);
        }
    }

    private void startCarbonServer(AutomationContext context, Map<String, String> startupParameters) throws Exception {

        CarbonTestServerManager server = new CarbonTestServerManager(context, System.getProperty("carbon.zip"),
                startupParameters);
        manager.startServers(server);
    }

    private void createServiceProviderInSecondaryIS() throws Exception {

        ServiceProvider serviceProvider = new ServiceProvider();
        serviceProvider.setApplicationName(SECONDARY_IS_APPLICATION_NAME);
        serviceProvider.setDescription("This is a test Service Provider");
        applicationManagementServiceClient2.createApplication(serviceProvider);

        serviceProvider = applicationManagementServiceClient2.getApplication(SECONDARY_IS_APPLICATION_NAME);

        InboundAuthenticationRequestConfig samlAuthenticationRequestConfig = new InboundAuthenticationRequestConfig();
        samlAuthenticationRequestConfig.setInboundAuthKey(SECONDARY_IS_APPLICATION_NAME);
        samlAuthenticationRequestConfig.setInboundAuthType("samlsso");
        org.wso2.carbon.identity.application.common.model.xsd.Property property = new org.wso2.carbon.identity.application.common.model.xsd.Property();

        samlAuthenticationRequestConfig
                .setProperties(new org.wso2.carbon.identity.application.common.model.xsd.Property[] { property });

        InboundAuthenticationConfig inboundAuthenticationConfig = new InboundAuthenticationConfig();
        inboundAuthenticationConfig.setInboundAuthenticationRequestConfigs(
                new InboundAuthenticationRequestConfig[] { samlAuthenticationRequestConfig });

        serviceProvider.setInboundAuthenticationConfig(inboundAuthenticationConfig);
        applicationManagementServiceClient2.updateApplicationData(serviceProvider);
    }

    private void createSAMLAppInSecondaryIS() throws Exception {

        SAMLSSOServiceProviderDTO samlssoServiceProviderDTO = new SAMLSSOServiceProviderDTO();
        samlssoServiceProviderDTO.setIssuer(SECONDARY_IS_APPLICATION_NAME);
        samlssoServiceProviderDTO.setAssertionConsumerUrls(new String[] { COMMON_AUTH_URL });
        samlssoServiceProviderDTO.setDefaultAssertionConsumerUrl(COMMON_AUTH_URL);
        samlssoServiceProviderDTO.setNameIDFormat(SAML_NAME_ID_FORMAT);
        samlssoServiceProviderDTO.setDoSignAssertions(false);
        samlssoServiceProviderDTO.setDoSignResponse(false);
        samlssoServiceProviderDTO.setDoSingleLogout(true);
        samlssoServiceProviderDTO.setEnableAttributeProfile(false);
        samlssoServiceProviderDTO.setEnableAttributesByDefault(false);

        samlSSOConfigServiceClient.addServiceProvider(samlssoServiceProviderDTO);
    }

    private void readConditionalAuthScript(String filename) throws Exception {

        try (InputStream resourceAsStream = this.getClass().getResourceAsStream(filename)) {
            BufferedInputStream bufferedInputStream = new BufferedInputStream(resourceAsStream);
            StringBuilder resourceFile = new StringBuilder();

            int c;
            while ((c = bufferedInputStream.read()) != -1) {
                char val = (char) c;
                resourceFile.append(val);
            }

            script = resourceFile.toString();
        } catch (IOException e) {
            String errorMsg = "Error occurred while reading file from class path, " + e.getMessage();
            log.error(errorMsg);
        }
    }

    /**
     * Update service provider authentication script config.
     *
     * @param filename File Name of the authentication script.
     * @throws Exception
     */
    private void updateAuthScript(String filename) throws Exception {

        readConditionalAuthScript(filename);
        AuthenticationScriptConfig config = new AuthenticationScriptConfig();
        config.setContent(script);
        config.setEnabled(true);
        outboundAuthConfig.setAuthenticationScriptConfig(config);
        serviceProvider.setLocalAndOutBoundAuthenticationConfig(outboundAuthConfig);
        applicationManagementServiceClient.updateApplicationData(serviceProvider);
    }
}

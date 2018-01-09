/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package io.fabric8.maven.rt;

import io.fabric8.openshift.api.model.Route;
import okhttp3.Response;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.lib.Repository;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.fabric8.kubernetes.assertions.Assertions.assertThat;

public class VertxConfigmapBoosterIT extends BaseBoosterIT {
    private final String SPRING_BOOT_CONFIGMAP_BOOSTER_GIT = "https://github.com/openshiftio-vertx-boosters/vertx-configmap-booster.git";

    private final String TESTSUITE_CONFIGMAP_NAME = "app-config";

    private final String EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL = "fabric8:deploy -DskipTests", EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE = "openshift";

    private final String TEST_ENDPOINT = "/api/greeting";

    private final String ANNOTATION_KEY = "vertx-configmap-testKey", ANNOTATION_VALUE = "vertx-configmap-testValue";

    private final String appConfigFile = "/app-config.yml";

    private final String RELATIVE_POM_PATH = "/pom.xml";

    @Test
    public void deploy_vertx_app_once() throws Exception {
        Repository testRepository = setupSampleTestRepository(SPRING_BOOT_CONFIGMAP_BOOSTER_GIT, RELATIVE_POM_PATH);

        createViewRoleToServiceAccount();
        createConfigMapResourceForApp(TESTSUITE_CONFIGMAP_NAME);
        deploy(testRepository, EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL, EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE);
        waitAfterDeployment(false);
        assertDeployment(false);

        openShiftClient.configMaps().inNamespace(testsuiteNamespace).withName(TESTSUITE_CONFIGMAP_NAME).delete();
    }

    @Test
    public void redeploy_vertx_app() throws Exception {
        Repository testRepository = setupSampleTestRepository(SPRING_BOOT_CONFIGMAP_BOOSTER_GIT, RELATIVE_POM_PATH);

        // Make some changes in ConfigMap and rollout
        createConfigMapResourceForApp(TESTSUITE_CONFIGMAP_NAME);
        updateSourceCode(testRepository, RELATIVE_POM_PATH);
        addRedeploymentAnnotations(testRepository, RELATIVE_POM_PATH, ANNOTATION_KEY, ANNOTATION_VALUE, fmpConfigurationFile);

        // 2. Re-Deployment
        deploy(testRepository, EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL, EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE);
        /*
         * Since the maintainers of this booster project have moved the configmap to
         * src/main/fabric8 directory the configmap resource gets created during the
         * time of compilation.
         */
        editConfigMapResourceForApp(TESTSUITE_CONFIGMAP_NAME);
        waitAfterDeployment(true);
        assertDeployment(true);

        openShiftClient.configMaps().inNamespace(testsuiteNamespace).withName(TESTSUITE_CONFIGMAP_NAME).delete();
    }

    private void deploy(Repository testRepository, String buildGoal, String buildProfile) throws Exception {
        runEmbeddedMavenBuild(testRepository, buildGoal, buildProfile);
    }

    private void waitAfterDeployment(boolean bIsRedeployed) throws Exception {
        // Waiting for application pod to start.
        if (bIsRedeployed)
            waitTillApplicationPodStarts(ANNOTATION_KEY, ANNOTATION_VALUE);
        else
            waitTillApplicationPodStarts();
        // Wait for Services, Route, ConfigMaps to refresh according to the deployment.
        TimeUnit.SECONDS.sleep(20);
    }

    private void assertDeployment(boolean bIsRedeployed) throws Exception {
        assertThat(openShiftClient).deployment(testsuiteRepositoryArtifactId);
        assertThat(openShiftClient).service(testsuiteRepositoryArtifactId);

        RouteAssert.assertRoute(openShiftClient, testsuiteRepositoryArtifactId);
        if (bIsRedeployed)
            assert assertApplicationEndpoint("content", "Bonjour, World from a ConfigMap !");
        else
            assert assertApplicationEndpoint("content", "Hello, World from a ConfigMap !");
    }

    private boolean assertApplicationEndpoint(String key, String value) throws Exception {
        Route applicationRoute = getApplicationRouteWithName(testsuiteRepositoryArtifactId);
        String hostUrl = applicationRoute.getSpec().getHost() + TEST_ENDPOINT;
        Response response = makeHttpRequest(HttpRequestType.GET, "http://" + hostUrl, null);
        return new JSONObject(response.body().string()).getString(key).equals(value);
    }

    private void createConfigMapResourceForApp(String configMapName) throws Exception {
        Map<String, String> configMapData = new HashMap<>();
        File aConfigMapFile = new File(getClass().getResource(appConfigFile).getFile());

        configMapData.put("app-config.yml", FileUtils.readFileToString(aConfigMapFile));

        createConfigMapResource(configMapName, configMapData);
    }

    private void editConfigMapResourceForApp(String configMapName) throws Exception {
        Map<String, String> configMapData = new HashMap<>();
        File aConfigMapFile = new File(getClass().getResource(appConfigFile).getFile());

        String content = FileUtils.readFileToString(aConfigMapFile);
        content = content.replace("Hello", "Bonjour");
        configMapData.put("app-config.yml", content);

        createOrReplaceConfigMap(configMapName, configMapData);
    }

    @After
    public void cleanup() throws Exception {
        cleanSampleTestRepository();
    }
}

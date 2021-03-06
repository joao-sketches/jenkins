package jenkins.model;

import java.net.HttpURLConnection;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.recipes.WithPlugin;

import hudson.PluginWrapper;
import hudson.cli.CLICommandInvoker;
import hudson.cli.DisablePluginCommand;
import hudson.model.Descriptor;
import hudson.model.MyView;
import hudson.model.View;
import hudson.model.labels.LabelAtom;
import hudson.tasks.Shell;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import static hudson.cli.CLICommandInvoker.Matcher.failedWith;

/**
 * As Jenkins.MANAGE can be enabled on startup with jenkins.security.ManagePermission property, we need a test class
 * with this property activated.
 */
// TODO move tests to indicated test classes when we no longer need to set the system property
public class JenkinsManagePermissionTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @BeforeClass
    public static void enableManagePermission() {
        System.setProperty("jenkins.security.ManagePermission", "true");
    }

    @AfterClass
    public static void disableManagePermission() {
        System.clearProperty("jenkins.security.ManagePermission");
    }


    // -----------------------------
    // DisablePluginCommandTest
    @Issue("JENKINS-60266")
    @Test
    @WithPlugin({ "depender-0.0.2.hpi", "dependee-0.0.2.hpi"})
    public void managerCannotDisablePlugin() {

        //GIVEN a user with Jenkins.MANAGE permission
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.MANAGE).everywhere().to("manager")
        );

        //WHEN trying to disable a plugin
        assertThat(disablePluginsCLiCommandAs("manager", "dependee"), failedWith(6));
        //THEN it's refused and the plugin is not disabled.
        assertPluginEnabled("dependee");
    }

    /**
     * Disable a list of plugins using the CLI command.
     * @param user Username
     * @param args Arguments to pass to the command.
     * @return Result of the command. 0 if succeed, 16 if some plugin couldn't be disabled due to dependent plugins.
     */
    private CLICommandInvoker.Result disablePluginsCLiCommandAs(String user, String... args) {
        return new CLICommandInvoker(j, new DisablePluginCommand()).asUser(user).invokeWithArgs(args);
    }


    private void assertPluginEnabled(String name) {
        PluginWrapper plugin = j.getPluginManager().getPlugin(name);
        assertThat(plugin, is(notNullValue()));
        assertTrue(plugin.isEnabled());
    }

    // End of DisablePluginCommandTest
    //-------

    // -----------------------------
    //ComputerTest
    @Issue("JENKINS-60266")
    @Test
    public void dumpExportTableForbiddenWithoutAdminPermission() throws Exception {
        final String READER = "reader";
        final String MANAGER = "manager";
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ).everywhere().to(READER)
                .grant(Jenkins.MANAGE).everywhere().to(MANAGER)
                .grant(Jenkins.READ).everywhere().to(MANAGER)
        );
        j.createWebClient().login(READER).assertFails("computer/(master)/dumpExportTable", HttpURLConnection.HTTP_FORBIDDEN);
        j.createWebClient().login(MANAGER).assertFails("computer/(master)/dumpExportTable", HttpURLConnection.HTTP_FORBIDDEN);
    }

    // End of ComputerTest
    //-------

    // -----------------------------
    // HudsonTest
    @Issue("JENKINS-60266")
    @Test
    public void someGlobalConfigurationIsNotDisplayedWithManagePermission() throws Exception {
        //GIVEN a user with Jenkins.MANAGE permission
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.MANAGE, Jenkins.READ).everywhere().toEveryone());

        //WHEN the user goes to /configure page
        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        String formText = form.asText();
        //THEN items restricted to ADMINISTER only should not be displayed.
        assertThat("Should be able to configure system message", formText, not(containsString("systemMessage")));
        assertThat("Should be able to configure project naming strategy", formText, not(containsString("useProjectNamingStrategy")));
        assertThat("Shouldn't be able to configure primary view", formText, not(containsString("primaryView")));
        assertThat("Shouldn't be able to configure # of executors", formText, not(containsString("executors")));
        assertThat("Shouldn't be able to configure Global properties", formText,
                not(containsString("Global properties")));
        assertThat("Shouldn't be able to configure Administrative monitors", formText, not(containsString(
                "Administrative "
                        + "monitors")));
        assertThat("Shouldn't be able to configure Shell", formText, not(containsString("Shell")));
    }

    @Issue("JENKINS-60266")
    @Test
    public void someGlobalConfigCanNotBeModifiedWithManagePermission() throws Exception {
        j.jenkins.addView(new MyView("testView", j.jenkins));

        //GIVEN the Global Configuration Form, with some changes unsaved
        int currentNumberExecutors = j.getInstance().getNumExecutors();
        String shell = getShell();
        View view = j.jenkins.getPrimaryView();
        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        form.getInputByName("_.numExecutors").setValueAttribute(""+(currentNumberExecutors+1));
        form.getInputByName("_.shell").setValueAttribute("/fakeShell");
        form.getSelectByName("primaryView").setSelectedAttribute("testView", true);

        // WHEN a user with only Jenkins.MANAGE permission try to save those changes
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.MANAGE, Jenkins.READ).everywhere().toEveryone());
        j.submit(form);
        // THEN the changes on fields forbidden to a Jenkins.MANAGE permission are not saved
        assertEquals("shouldn't be allowed to change the number of executors", currentNumberExecutors, j.getInstance().getNumExecutors());
        assertEquals("shouldn't be allowed to change the shell executable", shell, getShell());
        assertEquals("shouldn't be allowed to change the primary view", view, j.getInstance().getPrimaryView());
    }

    @Issue("JENKINS-60266")
    @Test
    public void globalConfigAllowedWithManagePermission() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.MANAGE, Jenkins.READ).everywhere().toEveryone());

        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        HtmlPage updated = j.submit(form);
        assertThat("User with Jenkins.MANAGE permission should be able to update global configuration",
                updated.getWebResponse(), hasResponseCode(HttpURLConnection.HTTP_OK));
    }

    private String getShell() {
        Descriptor descriptorByName = j.getInstance().getDescriptorByName("hudson.tasks.Shell");
        return ((Shell.DescriptorImpl) descriptorByName).getShell();
    }

    private static Matcher<WebResponse> hasResponseCode(final int httpStatus) {
        return new BaseMatcher<WebResponse>() {
            @Override
            public boolean matches(final Object item) {
                final WebResponse response = (WebResponse) item;
                return (response.getStatusCode() == httpStatus);
            }

            @Override
            public void describeTo(final Description description) {
                description.appendText("Jenkins to return  ").appendValue(httpStatus);
            }

            @Override
            public void describeMismatch(Object item, Description description) {
                WebResponse response = (WebResponse) item;
                description.appendText("Response code was: ");
                description.appendValue(response.getStatusCode());
                description.appendText(" with error message: ");
                description.appendText(response.getStatusMessage());
                description.appendText("\n with headers ").appendValueList("", "\n    ", "", response.getResponseHeaders());
                description.appendText("\nPage content: ").appendValue(response.getContentAsString());
            }
        };
    }

    // End of HudsonTest
    //-------
}

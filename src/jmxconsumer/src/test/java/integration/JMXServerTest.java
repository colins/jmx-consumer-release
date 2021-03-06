package integration;

import com.j256.simplejmx.client.JmxClient;
import org.cloudfoundry.jmxconsumer.ingress.Metric;
import org.cloudfoundry.jmxconsumer.jmx.JmxConsumerServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.management.*;
import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JMXServerTest {

  private static final String SHA1_FOR_ROOT = "DC76E9F0C0006E8F919E0C515C66DBBA3982F785";
  private static final String SHA1_FOR_PASSWORD = "5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8";
  JmxConsumerServer server;

  public void startTheServer(String username, String password, boolean withPrefix, long expiryTime) throws Exception {
    // create tempfile for password and access file
    File passwordFile = writeToConfigFile("password", username + " " + password);
    File authFile = writeToConfigFile("auth", username + " readonly");

    server = new JmxConsumerServer(
            44444,
            44445,
            withPrefix ? "opentsdb.nozzle." : "",
            expiryTime,
            passwordFile.getAbsolutePath(),
            authFile.getAbsolutePath(),
            null,
            null
    );
    server.start();
  }

  @AfterEach
  public void stopTheServer() throws IOException, InterruptedException {
    server.stop();
    Thread.sleep(1000);
  }

  private JmxClient getJmxClient() throws JMException {
    return getJmxClient("root", "root");
  }

  private JmxClient getJmxClient(String username, String password) throws JMException {
    String uri = String.format(
      "service:jmx:rmi://%s:%d/jndi/rmi://%s:%d/jmxrmi",
      "127.0.0.1",
      44445,
      "127.0.0.1",
      44444
    );
    JmxClient client = new JmxClient(uri, username, password);
    assertThat(client).isNotNull();
    return client;
  }

  @Test
  @DisplayName("Test adding metrics to server")
  public void addMetricToServer() throws Exception {
    startTheServer("root", SHA1_FOR_ROOT,false, 9999999999l);
    Map<String, String> metrics1Tags = new HashMap<String, String>();
    metrics1Tags.put("deployment", "deployment0");
    metrics1Tags.put("job", "job0");
    metrics1Tags.put("index", "index0");
    metrics1Tags.put("ip","0.0.0.0");
    server.addMetric(new Metric("system.cpu1", 1d, 0, metrics1Tags));

    Map<String, String> metric2Tags = new HashMap<String, String>();
    metric2Tags.put("deployment", "deployment1");
    metric2Tags.put("job", "job1");
    metric2Tags.put("index", "index1");
    metric2Tags.put("ip","1.1.1.1");
    server.addMetric(new Metric("system.cpu2", 2d, 0, metric2Tags));

    JmxClient client = getJmxClient();

    Set<ObjectName> beanNames = client.getBeanNames("org.cloudfoundry");
    ObjectName name = new ObjectName("org.cloudfoundry:deployment=deployment0,job=job0,index=index0,ip=0.0.0.0");
    assertThat(beanNames).contains(name);

    Object attribute = client.getAttribute(name, "system.cpu1");
    assertThat(attribute).isNotNull();
    assertThat((Double)attribute).isEqualTo(1d);

    beanNames = client.getBeanNames("org.cloudfoundry");
    name = new ObjectName("org.cloudfoundry:deployment=deployment1,job=job1,index=index1,ip=1.1.1.1");
    assertThat(beanNames).contains(name);

    attribute = client.getAttribute(name, "system.cpu2");
    assertThat(attribute).isNotNull();
    assertThat((Double)attribute).isEqualTo(2d);

    server.addMetric(new Metric("another.metric", 2d, 0, metric2Tags));
    beanNames = client.getBeanNames("org.cloudfoundry");
    name = new ObjectName("org.cloudfoundry:deployment=deployment1,job=job1,index=index1,ip=1.1.1.1");
    assertThat(beanNames).contains(name);

    attribute = client.getAttribute(name, "another.metric");
    assertThat(attribute).isNotNull();
    assertThat((Double)attribute).isEqualTo(2d);
  }

  @Test
  @DisplayName("When the same metric has timestamps that come out of order")
  public void sameMetricDifferentTimestamps() throws Exception {
    startTheServer("root", SHA1_FOR_ROOT,false, 9999999999l);
    Map<String, String> metrics1Tags = new HashMap<String, String>();
    metrics1Tags.put("deployment", "deployment0");
    metrics1Tags.put("job", "job0");
    metrics1Tags.put("index", "index0");
    metrics1Tags.put("ip","0.0.0.0");
    server.addMetric(new Metric("system.cpu1", 100d, 100, metrics1Tags));
    server.addMetric(new Metric("system.cpu1", 88d, 88, metrics1Tags));

    JmxClient client = getJmxClient();

    Set<ObjectName> beanNames = client.getBeanNames("org.cloudfoundry");
    ObjectName name = new ObjectName("org.cloudfoundry:deployment=deployment0,job=job0,index=index0,ip=0.0.0.0");
    assertThat(beanNames).contains(name);

    Object attribute = client.getAttribute(name, "system.cpu1");
    assertThat(attribute).isNotNull();
    assertThat((Double)attribute).isEqualTo(100d);
  }

  @Test
  @DisplayName("When the prefix is enabled it prepends each metric name")
  public void addPrefixToMetrics() throws Exception {
    startTheServer("root", SHA1_FOR_ROOT,true, 9999999999l);
    Map<String, String> metrics1Tags = new HashMap<String, String>();
    metrics1Tags.put("deployment", "deployment0");
    metrics1Tags.put("job", "job0");
    metrics1Tags.put("index", "index0");
    metrics1Tags.put("ip","0.0.0.0");
    server.addMetric(new Metric("testingPrefix", 100d, 100, metrics1Tags));

    JmxClient client = getJmxClient();

    Set<ObjectName> beanNames = client.getBeanNames("org.cloudfoundry");
    ObjectName name = new ObjectName("org.cloudfoundry:deployment=deployment0,job=job0,index=index0,ip=0.0.0.0");
    assertThat(beanNames).contains(name);

    Object attribute = client.getAttribute(name, "opentsdb.nozzle.testingPrefix");
    assertThat(attribute).isNotNull();
    assertThat((Double)attribute).isEqualTo(100d);
  }

  @Test
  @DisplayName("Bean is gone after specified expiry time")
  public void checkBeanExpiryTime() throws Exception {
    long expiryTime= 3000;

    // set the expiry time

    startTheServer("root", SHA1_FOR_ROOT,false, expiryTime);
    Map<String, String> metrics1Tags = new HashMap<String, String>();
    metrics1Tags.put("deployment", "deployment21");
    metrics1Tags.put("job", "job0");
    metrics1Tags.put("index", "index0");
    metrics1Tags.put("ip","0.0.0.0");
    server.addMetric(new Metric("testingPrefix", 100d, 100, metrics1Tags));

    JmxClient client = getJmxClient();
    Set<ObjectName> beanNames = client.getBeanNames("org.cloudfoundry");
    ObjectName name = new ObjectName("org.cloudfoundry:deployment=deployment21,job=job0,index=index0,ip=0.0.0.0");
    assertThat(beanNames).contains(name);

    Thread.sleep(expiryTime + 2000);

    metrics1Tags = new HashMap<String, String>();
    metrics1Tags.put("deployment", "deployment123");
    metrics1Tags.put("job", "job0");
    metrics1Tags.put("index", "index0");
    metrics1Tags.put("ip","0.0.0.0");
    server.addMetric(new Metric("testingPrefix", 100d, 100, metrics1Tags));
    metrics1Tags.put("deployment", "deployment1234");
    server.addMetric(new Metric("testingPrefix123", 100d, 100, metrics1Tags));
    metrics1Tags.put("deployment", "deployment1235");
    server.addMetric(new Metric("testingPrefix123", 100d, 100, metrics1Tags));
    metrics1Tags.put("deployment", "deployment12351");
    server.addMetric(new Metric("testingPrefix123", 100d, 100, metrics1Tags));
    metrics1Tags.put("deployment", "deployment12352");
    server.addMetric(new Metric("testingPrefix123", 100d, 100, metrics1Tags));

    //the caches runs after so many writes to the cache hash map
    //it does not run the "expiry" every time
    //
    // from the docs
    // Timed expiration is performed with periodic maintenance during writes and occasionally during reads, as discussed below
    //
    // That is the reason additional write operations above are necessary for this test to pass

    beanNames = client.getBeanNames("org.cloudfoundry");
    assertThat(beanNames).doesNotContain(name);

  }

  @Test
  @DisplayName("When username and password are specified for the server")
  public void checkValidAndUsername() throws Exception {
    startTheServer("root", SHA1_FOR_PASSWORD, false, 9999999999l);

    assertThat(getJmxClient("root", "password")).isNotNull();
    assertThatThrownBy(() -> getJmxClient()).hasMessage("Authentication failed. Invalid username or password");
  }

  private File writeToConfigFile(String filename, String content) throws IOException {
    File configFile = File.createTempFile(filename, ".cfg");
    BufferedWriter writer= new BufferedWriter(new FileWriter(configFile));
    writer.write(content);
    writer.close();
    return configFile;
  }
}

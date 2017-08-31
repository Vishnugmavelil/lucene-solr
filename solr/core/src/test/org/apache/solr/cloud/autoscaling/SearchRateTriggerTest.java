package org.apache.solr.cloud.autoscaling;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.solr.client.solrj.cloud.autoscaling.ReplicaInfo;
import org.apache.solr.client.solrj.cloud.autoscaling.TriggerEventType;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.params.AutoScalingParams;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.core.CoreContainer;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *
 */
public class SearchRateTriggerTest extends SolrCloudTestCase {

  private static final String PREFIX = SearchRateTriggerTest.class.getSimpleName() + "-";
  private static final String COLL1 = PREFIX + "collection1";
  private static final String COLL2 = PREFIX + "collection2";

  private AutoScaling.TriggerEventProcessor noFirstRunProcessor = event -> {
    fail("Did not expect the listener to fire on first run!");
    return true;
  };

  @BeforeClass
  public static void setupCluster() throws Exception {
    configureCluster(4)
        .addConfig("conf", configset("cloud-minimal"))
        .configure();
    CollectionAdminRequest.Create create = CollectionAdminRequest.createCollection(COLL1,
        "conf", 2, 2);
    CloudSolrClient solrClient = cluster.getSolrClient();
    create.setMaxShardsPerNode(1);
    create.process(solrClient);
    create = CollectionAdminRequest.createCollection(COLL2,
        "conf", 2, 2);
    create.setMaxShardsPerNode(1);
    create.process(solrClient);
  }

  @Test
  public void testTrigger() throws Exception {
    double rate = 1.0;
    CoreContainer container = cluster.getJettySolrRunners().get(0).getCoreContainer();
    URL baseUrl = cluster.getJettySolrRunners().get(1).getBaseUrl();
    long waitForSeconds = 4 + random().nextInt(5);
    Map<String, Object> props = createTriggerProps(waitForSeconds, rate);
    final List<TriggerEvent> events = new ArrayList<>();
    CloudSolrClient solrClient = cluster.getSolrClient();

    try (SearchRateTrigger trigger = new SearchRateTrigger("search_rate_trigger", props, container)) {
      trigger.setProcessor(noFirstRunProcessor);
      trigger.run();
      trigger.setProcessor(event -> events.add(event));

      // generate replica traffic
      String coreName = container.getLoadedCoreNames().iterator().next();
      String url = baseUrl.toString() + "/" + coreName;
      try (HttpSolrClient simpleClient = new HttpSolrClient.Builder(url).build()) {
        SolrParams query = params(CommonParams.Q, "*:*", CommonParams.DISTRIB, "false");
        for (int i = 0; i < 200; i++) {
          simpleClient.query(query);
        }
        Thread.sleep(5000);
        // should generate replica event
        trigger.run();
        assertEquals(1, events.size());
        TriggerEvent event = events.get(0);
        assertEquals(TriggerEventType.SEARCHRATE, event.eventType);
        List<ReplicaInfo> infos = (List<ReplicaInfo>)event.getProperty(AutoScalingParams.REPLICA);
        assertEquals(1, infos.size());
        ReplicaInfo info = infos.get(0);
        assertEquals(coreName, info.getCore());
        assertTrue((Double)info.getVariable(AutoScalingParams.RATE) > rate);
      }
      // close that jetty to remove the violation - alternatively wait for 1 min...
      cluster.stopJettySolrRunner(1);
      events.clear();
      SolrParams query = params(CommonParams.Q, "*:*");
      for (int i = 0; i < 200; i++) {
        solrClient.query(COLL1, query);
      }
      Thread.sleep(waitForSeconds * 1000 + 2000);
      trigger.run();
      // should generate collection event
      assertEquals(1, events.size());
      TriggerEvent event = events.get(0);
      Map<String, Double> hotCollections = (Map<String, Double>)event.getProperty(AutoScalingParams.COLLECTION);
      assertEquals(1, hotCollections.size());
      Double Rate = hotCollections.get(COLL1);
      assertNotNull(Rate);
      assertTrue(Rate > rate);
      events.clear();

      for (int i = 0; i < 1000; i++) {
        solrClient.query(COLL2, query);
      }
      Thread.sleep(waitForSeconds * 1000 + 2000);
      trigger.run();
      // should generate node and collection event
      assertEquals(1, events.size());
      event = events.get(0);
      Map<String, Double> hotNodes = (Map<String, Double>)event.getProperty(AutoScalingParams.NODE);
      assertEquals(3, hotNodes.size());
      hotNodes.forEach((n, r) -> assertTrue(n, r > rate));
      hotCollections = (Map<String, Double>)event.getProperty(AutoScalingParams.COLLECTION);
      assertEquals(2, hotCollections.size());
      Rate = hotCollections.get(COLL1);
      assertNotNull(Rate);
      assertTrue(Rate > rate);
      Rate = hotCollections.get(COLL2);
      assertNotNull(Rate);
      assertTrue(Rate > rate);
    }
  }

  private Map<String, Object> createTriggerProps(long waitForSeconds, double rate) {
    Map<String, Object> props = new HashMap<>();
    props.put("rate", rate);
    props.put("event", "searchRate");
    props.put("waitFor", waitForSeconds);
    props.put("enabled", true);
    List<Map<String, String>> actions = new ArrayList<>(3);
    Map<String, String> map = new HashMap<>(2);
    map.put("name", "compute_plan");
    map.put("class", "solr.ComputePlanAction");
    actions.add(map);
    map = new HashMap<>(2);
    map.put("name", "execute_plan");
    map.put("class", "solr.ExecutePlanAction");
    actions.add(map);
    props.put("actions", actions);
    return props;
  }
}
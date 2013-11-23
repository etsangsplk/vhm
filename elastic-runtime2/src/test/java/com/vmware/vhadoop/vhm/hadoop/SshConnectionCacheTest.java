package com.vmware.vhadoop.vhm.hadoop;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import com.jcraft.jsch.Session;
import com.vmware.vhadoop.vhm.hadoop.SshConnectionCache.Connection;
import com.vmware.vhadoop.vhm.hadoop.SshUtilities.Credentials;

public class SshConnectionCacheTest
{
   private static int MINIMUM_REQUIRED_HOST_ALIASES = 3;

   private static String aliases[];
   private static String username;
   /* this will need to be set in the environment for the test. */
   private static String password = System.getProperty("user.password");
   private static String privateKeyFile;

   private static String userHomePath;

   static File authorizedKeysBackup = null;
   static File authorizedKeysName = null;
   static File sshDir = null;

   TestSshConnectionCache cache;
   Credentials credentials;

   @AfterClass
   public static void cleanup() throws IOException {
      /* make sure that backup exists */
      Files.move(authorizedKeysBackup.toPath(), authorizedKeysName.toPath(), REPLACE_EXISTING);
   }

   @BeforeClass
   public static void setup() throws IOException, InterruptedException {
      /* we've got assumptions that we're on a unix distro, but go with linux */
      Assume.assumeTrue("Test setup assumes certain things, which I've rolled up into - must be linux", "linux".equalsIgnoreCase(System.getProperty("os.name")));

      username = System.getProperty("user.name");
      assertNotNull("system property user.name", username);

      userHomePath = System.getProperty("user.home");
      assertNotNull("system property user.home", userHomePath);

      sshDir = new File(userHomePath+"/.ssh");
      File keyBase = File.createTempFile("sshtest", "", sshDir);
      keyBase.deleteOnExit();

//      Process keygen = Runtime.getRuntime().exec("ssh-keygen -b 2048 -t rsa -N '' -C 'ssh test key' -f "+keyBase.getAbsolutePath());
      Process keygen = Runtime.getRuntime().exec(new String[] {"ssh-keygen", "-q", "-b", "2048", "-t", "rsa", "-N", "", "-C", "ssh test key", "-f", keyBase.getAbsolutePath()});
      /* shouldn't hurt to provide input for the overwrite prompt to overwrite the empty tempFile, -o not supported on some distros */
      keygen.getOutputStream().write('y');
      keygen.getOutputStream().write('\n');
      keygen.getOutputStream().flush();

      long deadline = System.currentTimeMillis() + 10000;
      int ret = -1;
      do {
         try {
            /* don't want to hang if this command doesn't return */
            ret = keygen.exitValue();
         } catch (IllegalThreadStateException e) { /* not finished yet */ }
      } while (ret != 0 && System.currentTimeMillis() < deadline);

      /* tidy up if it hung */
      if (ret == -1) {
         keygen.destroy();
      }

      BufferedReader stdout = new BufferedReader(new InputStreamReader(keygen.getInputStream()));
      BufferedReader stderr = new BufferedReader(new InputStreamReader(keygen.getErrorStream()));

      /* dump output for debugging */
      for (String line = stdout.readLine(); line != null; line = stdout.readLine()) {
         System.err.println(line);
      }
      for (String line = stderr.readLine(); line != null; line = stderr.readLine()) {
         System.err.println(line);
      }

      assertEquals("keygen process didn't complete successfully", 0, ret);

      /* add key to authorized hosts */
      authorizedKeysName = new File(sshDir.getAbsolutePath()+"/authorized_keys");
      authorizedKeysBackup = File.createTempFile("authorized_keys", "ssh_test_backup", sshDir);
      Files.copy(authorizedKeysName.toPath(), authorizedKeysBackup.toPath(), REPLACE_EXISTING);
      File pub = new File(keyBase.getAbsoluteFile()+".pub");
      String signature = Files.readAllLines(pub.toPath(), Charset.defaultCharset()).get(0);

      OutputStreamWriter out = new FileWriter(authorizedKeysName, true);
      out.write(signature);
      out.flush();
      out.close();

      /* get the host aliases we have available */
      List<String> hosts = Files.readAllLines(Paths.get("/etc/hosts"), Charset.defaultCharset());
      for (String entry : hosts) {
         /* expect ip name alias1 alias2 alias3 ... */
         if (entry.charAt(0) == '#') {
            continue;
         }

         if (entry.indexOf('#') != -1) {
            entry = entry.substring(0, entry.indexOf('#'));
         }

         /* get rid of leading and trailing white space */
         entry = entry.trim();

         if (!entry.startsWith("127.0.0.1")) {
            continue;
         }

         String details[] = entry.split("\\s");
         assertTrue("expected at least "+MINIMUM_REQUIRED_HOST_ALIASES+" aliases in hosts entry", details.length > MINIMUM_REQUIRED_HOST_ALIASES);

         aliases = Arrays.copyOfRange(details, 1, details.length);
         break;
      }
   }

   class TestSshConnectionCache extends SshConnectionCache {

      public TestSshConnectionCache(int capacity) {
         super(capacity);
      }

      @Override
      protected Session createSession(Connection connection) {
         return super.createSession(connection);
      }

      @Override
      protected boolean connectSession(Session session, Credentials credentials) {
         return super.connectSession(session, credentials);
      }

      @Override
      protected Session getSession(Connection connection) {
         return super.getSession(connection);
      }

      @Override
      protected void clearCache() {
         super.clearCache();
      }

      void assertSessionCachedAndConnected(Connection connection) {

         Map<Connection,Session> cache = getCache();

         if (connection == null) {
            fail("connection object is null.");
         } else {
            Session session = cache.get(connection);

            if (session == null)
               fail("Session is not in the cache.");

            if (!session.isConnected())
               fail("Session is cached but not connected.");
         }
      }

      void assertSessionEvicted(Connection connection, Session session) {

         Map<Connection,Session> cache = getCache();

         if (connection == null) {
            fail("argument: connection object is null.");

         } else if (session == null) {
            fail("argument: session object is null.");

         } else {
            assertNull(cache.get(connection));
            assertTrue(!session.isConnected());
         }
      }

      int getCacheSize() {
         return getCache().size();
      }
   }


   @Before
   public void populateCredentials() {
      credentials = new Credentials(username, password, privateKeyFile);
   }


   @Test
   /*basic sanity check, no cache operation*/
   public void connectionSanityCheck() throws IOException {

      TestSshConnectionCache cache = new TestSshConnectionCache(aliases.length);

      for (String alias : aliases) {
         Connection connection = new Connection(alias, SshUtilities.DEFAULT_SSH_PORT, credentials);
         Session session = cache.createSession(connection);

         assertNotNull(session);
         assertTrue(cache.connectSession(session, credentials));

         ByteArrayOutputStream out = new ByteArrayOutputStream();

         /* check if execute(.) works*/
         int returnVal = cache.execute(alias, SshUtilities.DEFAULT_SSH_PORT, credentials, "cd ~|pwd", out);
         assertEquals("Expected ssh'd cd+pwd command to return without error", 0, returnVal);

         /* '~' should be /home/username */
         assertEquals(userHomePath, out.toString().trim());

         /* check if copy(.) works*/
         String dataWritten = "test data";
         byte[] data = dataWritten.getBytes();
         String remoteDirectory =  System.getProperty("java.io.tmpdir") + "/";
         String remoteName = alias + ".dat";
         String permissions = "774";

         returnVal = cache.copy(alias, SshUtilities.DEFAULT_SSH_PORT, credentials, data, remoteDirectory, remoteName, permissions);
         assertEquals("Expected scp command to return without error", 0, returnVal);

         File testFile = new File(remoteDirectory + "/" + remoteName);
         assertTrue(testFile.exists());

         /*check data integrity*/
         out.reset();
         returnVal = cache.execute(alias, SshUtilities.DEFAULT_SSH_PORT, credentials, "cat "+ remoteDirectory + "/" + remoteName, out);

         assertEquals("Expected ssh'd cat command to return without error", 0, returnVal);
         assertTrue(out.toString().equals(dataWritten));
      }
   }


   @Test
   public void cachedConnectionIsReused() throws IOException {
      TestSshConnectionCache cache = new TestSshConnectionCache(aliases.length);
      assertEquals(0, cache.getCacheSize());

      /*initial connection with hosts*/
      for (String alias : aliases) {
         int returnVal = cache.execute(alias, SshUtilities.DEFAULT_SSH_PORT, credentials, "date", null);
         assertEquals("Expected ssh'd date command to return without error", 0, returnVal);
      }

      /*all sessions should get cached*/
      assertEquals(aliases.length, cache.getCacheSize());

      /*all sessions should be cached now and can be reused*/
      for (String alias : aliases) {
         Connection connection = new Connection(alias, SshUtilities.DEFAULT_SSH_PORT, credentials);
         cache.assertSessionCachedAndConnected(connection);

         /*reuse the session, exercise copy(.) and execute(.)*/
         String dataWritten = "test data";
         byte[] data = dataWritten.getBytes();
         String remoteDirectory =  System.getProperty("java.io.tmpdir") + "/";
         String remoteName = alias + ".dat";
         String permissions = "774";
         ByteArrayOutputStream out = new ByteArrayOutputStream();

         int returnVal = cache.copy(connection, data, remoteDirectory, remoteName, permissions);
         assertEquals("Expected scp command to return without error", 0, returnVal);

         File testFile = new File(remoteDirectory + "/" + remoteName);
         assertTrue(testFile.exists());

         /*check data integrity*/
         out.reset();
         returnVal = cache.execute(connection, "cat "+ remoteDirectory + "/" + remoteName, out);

         assertEquals("Expected ssh'd cat command to return without error", 0, returnVal);
         assertTrue(out.toString().equals(dataWritten));

         /*all sessions are still cached, no eviction happens in reuse*/
         assertEquals(aliases.length, cache.getCacheSize());
      }
   }


   @Test
   public void leastRecentlyUsedIsEvicted() throws IOException {

      int cacheCapacity = aliases.length/2;
      TestSshConnectionCache cache = new TestSshConnectionCache(cacheCapacity);
      assertEquals(0, cache.getCacheSize());

      int index;
      /*auxiliary data structure to record the order in which sessions are put into cache*/
      List<Connection> connectionList = new LinkedList<Connection>();
      List<Session> sessionList = new LinkedList<Session>();

      /*populate the cache and make it full*/
      for(index = 0; index <cacheCapacity; index++) {
         Connection connection = new Connection(aliases[index], SshUtilities.DEFAULT_SSH_PORT, credentials);

         int returnVal = cache.execute(connection, "date", null);
         assertEquals("Expected ssh'd date command to return without error", 0, returnVal);

         /*session should be in cache now*/
         cache.assertSessionCachedAndConnected(connection);

         /*record the order in which this session is put into cache*/
         Session session = cache.getSession(connection);
         connectionList.add(connection);
         sessionList.add(session);
      }

      assertEquals(cacheCapacity, cache.getCacheSize());

      /*From now on, each time a new session is put into cache, the eldest one is expected to be evicted*/
      for(index = cacheCapacity; index < aliases.length; index++) {
         Connection connection = new Connection(aliases[index], SshUtilities.DEFAULT_SSH_PORT, credentials);

         /*exercise copy(.) and execute(.) for the new session*/
         String dataWritten = "test data";
         byte[] data = dataWritten.getBytes();
         String remoteDirectory =  System.getProperty("java.io.tmpdir") + "/";
         String remoteName = aliases[index] + ".dat";
         String permissions = "774";
         ByteArrayOutputStream out = new ByteArrayOutputStream();

         int returnVal = cache.copy(connection, data, remoteDirectory, remoteName, permissions);
         assertEquals("Expected scp command to return without error", 0, returnVal);

         File testFile = new File(remoteDirectory + "/" + remoteName);
         assertTrue(testFile.exists());

         /*check data integrity*/
         out.reset();
         returnVal = cache.execute(connection, "cat "+ remoteDirectory + "/" + remoteName, out);

         assertEquals("Expected ssh'd cat command to return without error", 0, returnVal);
         assertTrue(out.toString().equals(dataWritten));

         assertEquals(cacheCapacity, cache.getCacheSize());

         /*new session should be in cache now*/
         cache.assertSessionCachedAndConnected(connection);

         /*record the order in which this session is put into cache*/
         Session session = cache.getSession(connection);
         connectionList.add(connection);
         sessionList.add(session);

         /*whether we have evicted the right (the eldest) Session (with index 0, head of the list) out of cache*/
         cache.assertSessionEvicted(connectionList.get(0), sessionList.get(0));

         connectionList.remove(0);
         sessionList.remove(0);
      }
   }


   @Ignore
   @Test
   public void evictedConnectionIsRecreated() {

   }

   @Ignore
   @Test
   public void previouslyEvictedCachedConnectionIsReused() {

   }

   @Ignore
   @Test
   public void evictedConnectionDoesntCloseWhileInUse() {

   }

   @Ignore
   @Test
   public void cachedDroppedConnectionIsRecreated() {

   }
}

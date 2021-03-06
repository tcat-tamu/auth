/*
 * Copyright 2014 Texas A&M Engineering Experiment Station
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.tamu.tcat.account.test;

import java.io.IOException;
import java.security.Principal;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.LoginContext;

import org.junit.Ignore;
import org.junit.Test;

import edu.tamu.tcat.account.jaas.LoginDataPrincipal;
import edu.tamu.tcat.account.jaas.ServiceProviderCallback;
import edu.tamu.tcat.account.login.LoginData;
import edu.tamu.tcat.account.test.internal.Activator;
import edu.tamu.tcat.crypto.CryptoProvider;
import edu.tamu.tcat.db.exec.sql.SqlExecutor;
import edu.tamu.tcat.osgi.services.util.ServiceHelper;

//TODO: get JAAS authn example working against database
//      write tcat.oss.account wrapper for use with REST
//        Token doLogin(String u, String p);
//        void doLogout(Token t);
//        String getUserInfo(String? key, Token t);
//          back by OSGI service that can cache user subj/principles, flush on logout or on token expiration
//        boolean hasPerm(String? perm, Token t);
//          back by OSGI service that can crawl user/group space and build cache to be flushed on live perm change
//      token: userid, exp time, ip addr, server secret, plus sha256 of uid+exp+ip+secret
//      not exposing Subject and Principals until needed
//      token returned in header, to be update in subsequent requests with moving timeout window

// use pbkdf2impl for password storage, start with 10k rounds and calibrate
// use securetokenimpl for token generation and processing

public class BasicJaasTest
{
   private static final Logger debug = Logger.getLogger(BasicJaasTest.class.getName());

   @Ignore
   @Test
   public void testConcurrentLogin() throws Exception
   {
      final AtomicReference<Exception> err = new AtomicReference<>();
      // Test login in a separate thread
      ExecutorService exec = Executors.newFixedThreadPool(3);
      Runnable lt =
      new Runnable(){
         @Override
         public void run()
         {
            try
            {
               debug.info("running");
               new DatabaseLoginStrategy().doLogin();
            }
            catch (Exception e)
            {
//               System.err.println("AuthN Failed!");
//               e.printStackTrace();
               err.set(e);
            }
         }
      };
      exec.submit(lt);
//      exec.execute(lt);
      exec.shutdown();
      exec.awaitTermination(10, TimeUnit.MINUTES);
      Exception ex = err.get();
      if (ex != null)
         throw ex;
      
      debug.info("done");
   }
   
   interface LoginResult
   {
      Subject getSubject();
      String getUsername();
   }
   
   interface LoginStrategy
   {
      LoginResult getResult();
      void doLogin() throws Exception;
   }
   
   static class DatabaseLoginStrategy
   {
      void doLogin() throws Exception
      {
         debug.info("doing login");
         String username = "paul.bilnoski";
         String password = "pass";
         CryptoProvider cp = CryptoUtil.getProvider();
         SqlExecutor dbexec = getDbExec();
         
         /*
          * After authentication, the Subject returned should contain principals for:
          *  - account name
          *  - first name
          *  - last name
          *  - account id
          *  - group assignments (by id)
          *  - role assignments (by id)
          * 
          * IF authenticating with LDAP, need to pull the Principals out of the subject and
          * look for com.sun.security.auth.UserPrincipal representing user account name. Could
          * also pull a com.sun.security.auth.LdapPrincipal for the LDAP distinguished-name.
          * 
          * IF authenticating with our custom provider, pull the Principals we know about
          * into our system.
          */
         LoginContext ctx = new LoginContext("tcat.oss", new CBH(username, password, cp, dbexec));
         ctx.login();
         Subject subj = ctx.getSubject();
         Set<Principal> principals = subj.getPrincipals();
         System.err.println("Login succeeded, found "+principals.size()+" principals");
         for (Principal p : principals)
         {
            System.err.println(p);
         }
         
         Set<LoginDataPrincipal> princsLogin = subj.getPrincipals(LoginDataPrincipal.class);
         if (!princsLogin.isEmpty())
         {
            LoginDataPrincipal dataPrincipal = princsLogin.iterator().next();
            LoginData loginData = dataPrincipal.getLoginData();
         }
         
         //TODO: need to research how to build custom Permission instances into a Policy or
         // ProtectionDomain or AccessControlContext or something...
         
         // Don't really need this, right? This needs a "do-as" permission, i.e. a "sudo"
//         Integer rv = Subject.doAs(subj, new PrivilegedExceptionAction<Integer>()
//         {
//
//            @Override
//            public Integer run() throws Exception
//            {
//               // TODO Auto-generated method stub
//               return null;
//            }
//         });
      }
      
      
      
      private SqlExecutor getDbExec()
      {
         try (ServiceHelper sh = new ServiceHelper(Activator.getDefault().getContext()))
         {
            SqlExecutor exec = sh.waitForService(SqlExecutor.class, 5_000);
            return exec;
         }
         catch (Exception e)
         {
            throw new IllegalStateException("Failed accessing database executor", e);
         }
      }

      static class CBH implements CallbackHandler
      {
         public final String username;
         public final String password;
         private final CryptoProvider cp;
         private SqlExecutor dbExec;
         
         public CBH(String u, String p, CryptoProvider cp, SqlExecutor exec)
         {
            this.username = u;
            this.password = p;
            this.cp = cp;
            this.dbExec = exec;
         }
         
         @Override
         public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException
         {
            debug.info("handling callbacks");
            for (Callback cb : callbacks)
            {
               debug.info("Got callback: " + cb.getClass() + " " + cb);
               if (cb instanceof NameCallback)
               {
                  NameCallback ncb = (NameCallback)cb;
                  ncb.setName(username);
                  continue;
               }
               
               if (cb instanceof PasswordCallback)
               {
                  PasswordCallback ncb = (PasswordCallback)cb;
                  ncb.setPassword(password.toCharArray());
                  continue;
               }
               
               if (cb instanceof ServiceProviderCallback)
               {
                  ServiceProviderCallback cpc = (ServiceProviderCallback)cb;
                  cpc.setService(CryptoProvider.class, cp);
                  cpc.setService(SqlExecutor.class, dbExec);
                  continue;
               }
               
               throw new UnsupportedCallbackException(cb);
            }
         }
      }
   }
   
}

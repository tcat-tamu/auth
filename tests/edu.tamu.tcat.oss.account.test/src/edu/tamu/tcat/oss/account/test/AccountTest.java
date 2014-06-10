package edu.tamu.tcat.oss.account.test;

import java.io.IOException;
import java.security.Principal;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.LoginContext;

import org.junit.Test;

public class AccountTest
{
   private static final Logger debug = Logger.getLogger(AccountTest.class.getName());

   @Test
   public void testConcurrentLogin() throws Exception
   {
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
               new LoginTester().doLogin();
            }
            catch (Exception e)
            {
               System.err.println("Failed");
               e.printStackTrace();
            }
         }
      };
      exec.execute(lt);
//      exec.execute(lt);
      exec.shutdown();
      exec.awaitTermination(10, TimeUnit.MINUTES);
      debug.info("done");
   }
   
   static class LoginTester
   {
      void doLogin() throws Exception
      {
         debug.info("doing login");
         String username = "paulb";
         String password = "pass";
         
         LoginContext ctx = new LoginContext("tcat.oss", new CBH(username, password));
         ctx.login();
         Subject subj = ctx.getSubject();
         Set<Principal> principals = subj.getPrincipals();
         System.err.println("Login succeeded, found "+principals.size()+" principals");
         for (Principal p : principals)
         {
            System.err.println(p);
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
   }
   
   static class CBH implements CallbackHandler
   {
      public final String username;
      public final String password;
      
      public CBH(String u, String p)
      {
         this.username = u;
         this.password = p;
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
            
            throw new UnsupportedCallbackException(cb);
         }
      }
   }
   
}

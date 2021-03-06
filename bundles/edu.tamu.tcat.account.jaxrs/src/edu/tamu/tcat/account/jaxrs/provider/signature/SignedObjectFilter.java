package edu.tamu.tcat.account.jaxrs.provider.signature;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import edu.tamu.tcat.account.AccountException;
import edu.tamu.tcat.account.jaxrs.bean.ContextBean;
import edu.tamu.tcat.account.jaxrs.bean.SignatureSecured;
import edu.tamu.tcat.account.signature.SignatureException;
import edu.tamu.tcat.account.signature.SignatureService;
import edu.tamu.tcat.account.signature.SignatureService.Verifier;

public class SignedObjectFilter<PayloadType> implements ContainerRequestFilter
{
   private static final Logger debug = Logger.getLogger(SignedObjectFilter.class.getName());
   
   private SignatureService<PayloadType> signatureService;
   private SignatureSecured annot;
   
   public SignedObjectFilter(SignatureService<PayloadType> signatureService, SignatureSecured annot)
   {
      this.signatureService = signatureService;
      this.annot = annot;
   }
   
   @Override
   public void filter(ContainerRequestContext requestContext) throws IOException
   {
      PartialContext<PayloadType> partialContext = parseAuthorizationToken(requestContext);
      
      PayloadType payload;
      try
      {
         payload = signatureService.getPayload(partialContext.accountIdentifier);
      }
      catch (SignatureException e)
      {
         throw buildBadRequestException("Invalid authorization format");
      }
      catch (AccountException e)
      {
         debug.log(Level.SEVERE, "Could not process account", e);
         throw new ServerErrorException(Response.Status.INTERNAL_SERVER_ERROR);
      }
      String method = requestContext.getMethod();
      Verifier verifier;
      if (signatureService.mayBeSelfSigned())
      {
         if (payload == null && method.equals("GET"))
            throw new ForbiddenException();
         verifier = partialContext.selfSignedVerifier = signatureService.getVerifier(partialContext.signature);
      }
      else
      {
         if (payload == null)
            throw new ClientErrorException(Response.Status.UNAUTHORIZED);
         partialContext.payload = payload;
         verifier = partialContext.verifier = signatureService.getVerifier(payload, partialContext.signature);
      }
      
      String path = requestContext.getUriInfo().getPath();
      if (!path.startsWith("/"))
         path = '/' + path;
      
      MultivaluedMap<String, String> requestHeaders = requestContext.getHeaders();
      verifier.validateAdditionalHeaders(method, requestHeaders);
      
      StringBuilder buffer = new StringBuilder(method).append(' ').append(path).append('\n');
      TreeMap<String, List<String>> headers = new TreeMap<>(verifier.getSignedHeaders(new HashMap<>(requestHeaders)));
      for (Map.Entry<String, List<String>> header : headers.entrySet())
      {
         String key = header.getKey();
         for (String value : header.getValue())
         {
            buffer.append(key + ": ").append(value).append("\n");
         }
      }
      
      String signPrefix = buffer.append('\n').toString();
      partialContext.signPrefix = signPrefix;
      
      try
      {
         if (method.equals("GET"))
         {
            verifier.processSignedData(signPrefix.getBytes());
            if (!verifier.verify())
               throw buildBadRequestException("Failed integrity");
            ContextBean.from(requestContext).install(signatureService.getPayloadType()).set(annot.label(), payload);
         }
         else
         {
            ContextBean.from(requestContext).install(PartialContext.class).set("", partialContext);
         }
      }
      catch (SignatureException e)
      {
         throw buildBadRequestException("Could not process signature");
      }
      catch (AccountException e)
      {
         debug.log(Level.SEVERE, "Could not process signature", e);
         throw new InternalServerErrorException();
      }
   }
   
   protected PartialContext<PayloadType> parseAuthorizationToken(ContainerRequestContext requestContext)
   {
      String authHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
      String authorizationScope = signatureService.getAuthorizationScope();
      if (authHeader == null)
         throw new NotAuthorizedException(authorizationScope);
      if (!authHeader.startsWith(authorizationScope + " "))
         throw buildBadRequestException("Invalid authorization scope");
      authHeader = authHeader.substring(authorizationScope.length() + 1);
      String[] split = authHeader.split(":");
      if (split.length != 2)
         throw buildBadRequestException("Invalid authorization format");
      byte[] signature;
      try
      {
         signature = Base64.getDecoder().decode(split[1]);
      }
      catch (IllegalArgumentException e)
      {
         throw buildBadRequestException("Invalid authorization format");
      }
      String identifier = split[0];
      return new PartialContext<>(identifier, signature);
   }
   
   private static BadRequestException buildBadRequestException(String clientMessage)
   {
      return new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
            .entity(clientMessage)
            .type(MediaType.TEXT_PLAIN)
            .build());
   }
}

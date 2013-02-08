package restx;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.CaseFormat;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import restx.common.Crypto;
import restx.factory.Factory;
import restx.jackson.FrontObjectMapperFactory;

import javax.servlet.http.Cookie;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

/**
 * User: xavierhanin
 * Date: 2/6/13
 * Time: 9:53 PM
 */
public class RestxMainRouter {
    private static final String RESTX_SESSION_SIGNATURE = "RestxSessionSignature";
    private static final String RESTX_SESSION = "RestxSession";

    private final Logger logger = LoggerFactory.getLogger(RestxMainRouter.class);

    private Factory factory;
    private RestxRouter mainRouter;
    private RestxSession.Definition sessionDefinition;
    private ObjectMapper mapper;
    private byte[] signatureKey;

    public void init() {
        if (getLoadFactoryMode().equals("onstartup")) {
            loadFactory("");
            String baseUri = System.getProperty("restx.baseUri", "");
            if (!baseUri.isEmpty()) {
                logger.info("\n" +
                        "--------------------------------------\n" +
                        " -- RESTX READY\n" +
                        " -- " + mainRouter.getNbRoutes() + " routes - " + factory.getNbMachines() + " factory machines\n" +
                        " -- for a list of available routes,\n" +
                        " --   VISIT " + baseUri + "/404\n" +
                        " --\n");
            } else {
                logger.info("RESTX READY");
            }
        }
    }

    private void loadFactory(String context) {
        factory = Factory.builder()
                .addFromServiceLoader()
                .addLocalMachines(Factory.LocalMachines.threadLocal())
                .addLocalMachines(Factory.LocalMachines.contextLocal(context))
                .build();

        logger.debug("restx factory ready: {}", factory);

        sessionDefinition = new RestxSession.Definition(factory.getComponents(RestxSession.Definition.Entry.class));
        mapper = factory.getNamedComponent(FrontObjectMapperFactory.NAME).get().getComponent();
        signatureKey = Iterables.getFirst(factory.getComponents(SignatureKey.class),
                new SignatureKey("this is the default signature key".getBytes())).getKey();

        mainRouter = new RestxRouter("MainRouter", ImmutableList.copyOf(factory.getComponents(RestxRoute.class)));
    }


    public void route(String contextName, RestxRequest restxRequest, final RestxResponse restxResponse) throws IOException {
        logger.info("<< {}", restxRequest);
        if (getLoadFactoryMode().equals("onrequest")) {
            loadFactory(contextName);
        }

        final RestxSession session = buildContextFromRequest(restxRequest);
        RestxSession.setCurrent(session);
        try {
            if (!mainRouter.route(restxRequest, restxResponse, new RouteLifecycleListener() {
                @Override
                public void onRouteMatch(RestxRoute source) {
                }

                @Override
                public void onBeforeWriteContent(RestxRoute source) {
                    RestxSession newSession = RestxSession.current();
                    if (newSession != session) {
                        updateSessionInClient(restxResponse, newSession);
                    }
                }
            })) {
                String path = restxRequest.getRestxPath();
                String msg = String.format(
                        "no restx route found for %s %s\n" +
                        "routes:\n" +
                        "-----------------------------------\n" +
                        "%s\n" +
                        "-----------------------------------",
                        restxRequest.getHttpMethod(), path, mainRouter);
                restxResponse.setStatus(404);
                restxResponse.setContentType("text/plain");
                PrintWriter out = restxResponse.getWriter();
                out.print(msg);
                out.close();
            }
        } catch (JsonProcessingException ex) {
            logger.debug("request raised " + ex.getClass().getSimpleName(), ex);
            restxResponse.setStatus(400);
            restxResponse.setContentType("text/plain");
            PrintWriter out = restxResponse.getWriter();
            if (restxRequest.getContentStream() instanceof BufferedInputStream) {
                try {
                    JsonLocation location = ex.getLocation();
                    restxRequest.getContentStream().reset();
                    out.println(CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, ex.getClass().getSimpleName()) + "." +
                            " Please verify your input:");
                    out.println("<-- JSON -->");
                    List<String> lines = CharStreams.readLines(
                            new InputStreamReader(restxRequest.getContentStream()));
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        out.println(line);
                        if (i + 1 == location.getLineNr()) {
                            out.print(Strings.repeat(" ", location.getColumnNr() - 2));
                            out.println("^");
                            out.print(">> ");
                            out.print(Strings.repeat(" ", Math.max(0, location.getColumnNr()
                                    - (ex.getOriginalMessage().length() / 2) - 3)));
                            out.print(ex.getOriginalMessage());
                            out.println(" <<");
                            out.println();
                        }
                    }
                    out.println("</- JSON -->");

                    restxRequest.getContentStream().reset();
                    logger.debug(ex.getClass().getSimpleName() + " on " + restxRequest + "." +
                            " message: " + ex.getMessage() + "." +
                            " request content: " + CharStreams.toString(new InputStreamReader(restxRequest.getContentStream())));
                } catch (IOException e) {
                    logger.warn("io exception raised when trying to provide original input to caller", e);
                    out.println(ex.getMessage());
                }
            }
            out.close();
        } catch (IllegalArgumentException ex) {
            logger.debug("request raised IllegalArgumentException", ex);
            restxResponse.setStatus(400);
            restxResponse.setContentType("text/plain");
            PrintWriter out = restxResponse.getWriter();
            out.print(ex.getMessage());
            out.close();
        } finally {
            try { restxRequest.closeContentStream(); } catch (Exception ex) { }
            RestxSession.setCurrent(null);
        }
    }

    private String getLoadFactoryMode() {
        return System.getProperty("restx.factory.load", "onstartup");
    }

    private RestxSession buildContextFromRequest(RestxRequest req) throws IOException {
        String cookie = req.getCookieValue(RESTX_SESSION, "");
        if (cookie.trim().isEmpty()) {
            return new RestxSession(sessionDefinition, ImmutableMap.<String,String>of());
        } else {
            String sig = req.getCookieValue(RESTX_SESSION_SIGNATURE, "");
            if (!Crypto.sign(cookie, signatureKey).equals(sig)) {
                throw new IllegalArgumentException("invalid restx session signature");
            }
            return new RestxSession(sessionDefinition, ImmutableMap.copyOf(mapper.readValue(cookie, Map.class)));
        }
    }

    private void updateSessionInClient(RestxResponse resp, RestxSession session) {
        try {
            String sessionJson = mapper.writeValueAsString(session.valueidsByKeyMap());
            resp.addCookie(new Cookie(RESTX_SESSION, sessionJson));
            resp.addCookie(new Cookie(RESTX_SESSION_SIGNATURE, Crypto.sign(sessionJson, signatureKey)));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

}

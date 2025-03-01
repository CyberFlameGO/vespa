// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.clustercontroller.core.status;

import com.yahoo.exception.ExceptionUtils;
import com.yahoo.vespa.clustercontroller.core.RealTimer;
import com.yahoo.vespa.clustercontroller.core.Timer;
import com.yahoo.vespa.clustercontroller.core.status.statuspage.StatusPageResponse;
import com.yahoo.vespa.clustercontroller.core.status.statuspage.StatusPageServer;
import com.yahoo.vespa.clustercontroller.utils.communication.http.HttpRequest;
import com.yahoo.vespa.clustercontroller.utils.communication.http.HttpRequestHandler;
import com.yahoo.vespa.clustercontroller.utils.communication.http.HttpResult;

import java.io.FileNotFoundException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StatusHandler implements HttpRequestHandler {

    private final static Logger log = Logger.getLogger(StatusHandler.class.getName());

    public interface ClusterStatusPageServerSet {

        ContainerStatusPageServer get(String cluster);
        Map<String, ContainerStatusPageServer> getAll();

    }

    public static class ContainerStatusPageServer {

        StatusPageServer.HttpRequest request;
        StatusPageResponse response;
        // Ensure only one use the server at a time
        private final Object queueMonitor = new Object();
        // Lock safety with fleetcontroller. Wait until completion
        private final Object answerMonitor = new Object();

        public int getPort() { return 0; }
        public StatusPageServer.HttpRequest getCurrentHttpRequest() {
            synchronized (answerMonitor) {
                StatusPageServer.HttpRequest r = request;
                request = null;
                return r;
            }
        }

        public void fetchStatusPage(StatusPageServer.HttpRequest httpRequest,
                                    StatusPageServer.PatternRequestRouter statusRequestRouter,
                                    Timer timer) {
            StatusPageResponse.ResponseCode responseCode;
            String message;
            final String hiddenMessage;
            try {
                StatusPageServer.RequestHandler handler = statusRequestRouter.resolveHandler(httpRequest);
                if (handler == null) {
                    throw new FileNotFoundException("No handler found for request: " + httpRequest.getPath());
                }
                answerCurrentStatusRequest(handler.handle(httpRequest));
                return;
            } catch (FileNotFoundException e) {
                responseCode = StatusPageResponse.ResponseCode.NOT_FOUND;
                message = e.getMessage();
                hiddenMessage = "";
            } catch (Exception e) {
                responseCode = StatusPageResponse.ResponseCode.INTERNAL_SERVER_ERROR;
                message = "Internal Server Error";
                hiddenMessage = ExceptionUtils.getStackTraceAsString(e);
            }

            TimeZone tz = TimeZone.getTimeZone("UTC");
            long currentTime = timer.getCurrentTimeInMillis();
            StatusPageResponse response = new StatusPageResponse();
            StringBuilder content = new StringBuilder();
            response.setContentType("text/html");
            response.setResponseCode(responseCode);
            content.append("<!-- Answer to request ").append(httpRequest.getRequest()).append(" -->\n");
            content.append("<p>UTC time when creating this page: ").append(RealTimer.printDateNoMilliSeconds(currentTime, tz)).append("</p>");
            response.writeHtmlHeader(content, message);
            response.writeHtmlFooter(content, hiddenMessage);
            response.writeContent(content.toString());


            answerCurrentStatusRequest(response);
        }

        public void answerCurrentStatusRequest(StatusPageResponse r) {
            synchronized (answerMonitor) {
                response = r;
                answerMonitor.notify();
            }
        }

        StatusPageResponse getStatus(StatusPageServer.HttpRequest req) throws InterruptedException {
            synchronized (queueMonitor) {
                synchronized (answerMonitor) {
                    request = req;
                    while (response == null) {
                        answerMonitor.wait();
                    }
                    StatusPageResponse res = response;
                    response = null;
                    return res;
                }
            }
        }

    }

    private static final Pattern clusterListRequest = Pattern.compile("^/clustercontroller-status/v1/?$");
    private static final Pattern statusRequest = Pattern.compile("^/clustercontroller-status/v1/([^/]+)(/.*)?$");
    private final ClusterStatusPageServerSet statusClusters;

    public StatusHandler(ClusterStatusPageServerSet set) {
        statusClusters = set;
    }

    @Override
    public HttpResult handleRequest(HttpRequest httpRequest) throws Exception {
        log.fine("Handling status request " + httpRequest);
        Matcher matcher = statusRequest.matcher(httpRequest.getPath());
        if (matcher.matches()) {
            return handleClusterRequest(matcher.group(1), matcher.group(2));
        }
        matcher = clusterListRequest.matcher(httpRequest.getPath());
        if (matcher.matches()) {
            return handleClusterListRequest();
        }
        return new HttpResult().setHttpCode(
                404, "No page for request '" + httpRequest.getPath() + "'.");
    }

    private HttpResult handleClusterRequest(String clusterName, String fleetControllerPath) throws InterruptedException {
        ContainerStatusPageServer statusServer = statusClusters.get(clusterName);
        if (statusServer == null) {
            return new HttpResult().setHttpCode(
                    404, "No controller exists for cluster '" + clusterName + "'.");
        }
        if (fleetControllerPath == null || fleetControllerPath.isEmpty()) {
            fleetControllerPath = "/";
        }
        StatusPageServer.HttpRequest req = new StatusPageServer.HttpRequest(fleetControllerPath);
        StatusPageResponse response = statusServer.getStatus(req);
        HttpResult result = new HttpResult();
        if (response.getResponseCode() != null) {
            result.setHttpCode(
                    response.getResponseCode().getCode(),
                    response.getResponseCode().getMessage());
        }
        if (response.getContentType() != null) {
            result.addHeader("Content-Type", response.getContentType());
        }
        result.setContent(response.getOutputStream().toString(StandardCharsets.UTF_8));
        return result;
    }

    public HttpResult handleClusterListRequest() {
        HttpResult result = new HttpResult();
        result.addHeader("Content-Type", "text/html");
        StringWriter sw = new StringWriter();
        sw.append("<title>clusters</title>\n");
        for (String s : statusClusters.getAll().keySet()) {
            sw.append("<a href=\"./").append(s).append("\">").append(s)
                    .append("</a><br>").append("\n");
        }
        result.setContent(sw.toString());
        return result;
    }

}

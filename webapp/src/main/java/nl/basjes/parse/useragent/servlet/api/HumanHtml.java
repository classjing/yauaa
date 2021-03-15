/*
 * Yet Another UserAgent Analyzer
 * Copyright (C) 2013-2021 Niels Basjes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nl.basjes.parse.useragent.servlet.api;

import io.swagger.annotations.Api;
import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.Version;
import nl.basjes.parse.useragent.servlet.exceptions.MissingUserAgentException;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static nl.basjes.parse.useragent.servlet.ParseService.getInitStartMoment;
import static nl.basjes.parse.useragent.servlet.ParseService.getUserAgentAnalyzer;
import static nl.basjes.parse.useragent.servlet.ParseService.getUserAgentAnalyzerFailureMessage;
import static nl.basjes.parse.useragent.servlet.ParseService.userAgentAnalyzerIsAvailable;
import static nl.basjes.parse.useragent.servlet.api.Utils.splitPerFilledLine;
import static nl.basjes.parse.useragent.servlet.utils.Constants.GIT_REPO_URL;
import static nl.basjes.parse.useragent.version.YauaaVersion.getVersion;
import static org.apache.commons.text.StringEscapeUtils.escapeHtml4;

@Api(tags = "Yauaa")
//@RequestMapping(value = "/")
@RestController
public class HumanHtml {

    // =============== Memory Usage ===============

    private static final long MEGABYTE = 1024L * 1024L;

    public static long bytesToMegabytes(long bytes) {
        return bytes / MEGABYTE;
    }

    private static String getMemoryUsage() {
        // Get the Java runtime
        Runtime runtime = Runtime.getRuntime();
        runtime.gc(); // NOSONAR: We do gc to get a bit more accurate number of the actually memory usage (java:S1215)
        // Calculate the used memory
        long memory = runtime.totalMemory() - runtime.freeMemory();
        return String.format("Used memory is %d MiB", bytesToMegabytes(memory));
    }

    // =============== HTML (Human usable) OUTPUT ===============

    @GetMapping(
        value = "/",
        produces = MediaType.TEXT_HTML_VALUE
    )
    public String getHtml(@RequestHeader("User-Agent") String userAgentString,
                          @RequestParam(name = "ua", required = false) String userAgentStringParam) {
        if (userAgentStringParam == null || userAgentStringParam.isEmpty()) {
            return doHTML(userAgentString);
        } else {
            return doHTML(userAgentStringParam);
        }
    }

    @PostMapping(
        value = "/",
        consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        produces = MediaType.TEXT_HTML_VALUE
    )
    public String getHtmlPOST(@ModelAttribute("useragent") String userAgent) {
        return doHTML(userAgent);
    }

    // ===========================================

    private String doHTML(String userAgentString) {
        long start      = System.nanoTime();
        long startParse = 0;
        long stopParse  = 0;

        if (userAgentString == null) {
            throw new MissingUserAgentException();
        }

        StringBuilder sb = new StringBuilder(4096);
        try {
            htmlHead(sb);

            if (userAgentAnalyzerIsAvailable()) {
                startParse = System.nanoTime();

                List<UserAgent> userAgents = new ArrayList<>();
                final List<String> userAgentStrings = splitPerFilledLine(userAgentString);
                userAgentStrings.forEach(ua -> userAgents.add(getUserAgentAnalyzer().parse(ua)));
                stopParse = System.nanoTime();

                for (UserAgent userAgent: userAgents) {

                    sb.append("<hr/>");
                    sb.append("<h2 class=\"title\">The UserAgent</h2>");
                    sb.append("<p class=\"input\">").append(escapeHtml4(userAgent.getUserAgentString())).append("</p>");
                    sb.append("<h2 class=\"title\">The analysis result</h2>");
                    sb.append("<table id=\"result\">");
                    sb.append("<tr><th colspan=2>Field</th><th>Value</th></tr>");

                    Map<String, Integer>                     fieldGroupCounts = new HashMap<>();
                    List<Pair<String, Pair<String, String>>> fields           = new ArrayList<>(32);
                    for (String fieldname : userAgent.getAvailableFieldNamesSorted()) {
                        Pair<String, String> split = prefixSplitter(fieldname);
                        fields.add(new ImmutablePair<>(fieldname, split));
                        Integer count = fieldGroupCounts.get(split.getLeft());
                        if (count == null) {
                            count = 1;
                        } else {
                            count++;
                        }
                        fieldGroupCounts.put(split.getLeft(), count);
                    }

                    String currentGroup = "";
                    for (Pair<String, Pair<String, String>> field : fields) {
                        String fieldname  = field.getLeft();
                        String groupName  = field.getRight().getLeft();
                        String fieldLabel = field.getRight().getRight();
                        sb.append("<tr>");
                        if (!currentGroup.equals(groupName)) {
                            currentGroup = groupName;
                            sb.append("<td rowspan=").append(fieldGroupCounts.get(currentGroup)).append("><b><u>")
                                .append(escapeHtml4(currentGroup)).append("</u></b></td>");
                        }
                        sb.append("<td>").append(camelStretcher(escapeHtml4(fieldLabel))).append("</td>")
                            .append("<td>").append(escapeHtml4(userAgent.getValue(fieldname))).append("</td>")
                            .append("</tr>");
                    }
                    sb.append("</table>");
                }

                documentationBlock(sb, userAgents);

                sb.append("<hr/>");
                sb.append("<form class=\"logobar tryyourown\" action=\"\" method=\"post\">");
                sb.append("<label for=\"useragent\">Manual testing of a useragent:</label>");

                sb.append("<textarea id=\"useragent\" name=\"useragent\" maxlength=\"2000\" rows=\"4\" " +
                    "placeholder=\"Paste the useragent you want to test...\">")
//                    .append(escapeHtml4(userAgentString))
                    .append("</textarea>");
                sb.append("<input class=\"testButton\" type=\"submit\" value=\"Analyze\">");
                sb.append("</form>");
                sb.append("<br/>");

                sb.append("<hr/>");
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 7.8.9; nl-nl ; " +
                        "Niels Ultimate 42 demo phone Build/42 ; " +
                        "https://yauaa.basjes.nl ) " +
                        "AppleWebKit/8.4.7.2 (KHTML, like Gecko) " +
                        "Yet another browser/3.1415926 Mobile Safari/6.6.6";
                sb.append("<form class=\"logobar tryexample\" action=\"\" method=\"post\">");
                sb.append("<input type=\"hidden\" id=\"demouseragent\" name=\"useragent\" " +
                    "value=\"").append(escapeHtml4(userAgentString)).append("\">");
                sb.append("<input class=\"demoButton\" type=\"submit\" value=\"Try this demonstration UserAgent\">");
                sb.append("</form>");

                sb.append("<hr/>");
            } else {
                stillStartingUp(sb);
            }
        } catch (Exception e) {
            sb.append("<div class=\"failureBorder\">");
            sb.append("<p class=\"failureContent\">An exception occurred during parsing</p>");
            sb.append("<p class=\"failureContent\">Exception: ").append(e.getClass().getSimpleName()).append("</p>");
            sb.append("<p class=\"failureContent\">Message: ").append(e.getMessage().replace("\\n", "<br/>")).append("</p>");
            sb.append("</div>");
        } finally {
            long   stop              = System.nanoTime();
            double pageMilliseconds  = (stop - start) / 1000000.0;
            double parseMilliseconds = (stopParse - startParse) / 1000000.0;
            sb.append("<p class=\"speed\">Building this page took ")
                .append(String.format(Locale.ENGLISH, "%3.3f", pageMilliseconds))
                .append(" ms.</p>");

            if (parseMilliseconds > 0) {
                sb.append("<p class=\"speed\">Parsing took ")
                    .append(String.format(Locale.ENGLISH, "%3.3f", parseMilliseconds))
                    .append(" ms.</p>");
            }

            htmlFooter(sb);
        }
        return sb.toString();
    }

    private void stillStartingUp(StringBuilder sb) {
        long   now        = System.currentTimeMillis();
        long   millisBusy = now - getInitStartMoment();
        String timeString = String.format("%3.1f", ((double) millisBusy / 1000.0));

        String userAgentAnalyzerFailureMessage = getUserAgentAnalyzerFailureMessage();
        if (userAgentAnalyzerFailureMessage == null) {
            sb
                .append("<div class=\"notYetStartedBorder\">")
                .append("<p class=\"notYetStarted\">The analyzer is currently starting up.</p>")
                .append("<p class=\"notYetStarted\">It has been starting up for <u>").append(timeString).append("</u> seconds</p>")
                .append("<p class=\"notYetStarted\">Anything between 5-15 seconds is normal.</p>")
                .append("<p class=\"notYetStartedAppEngine\">On a free AppEngine 50 seconds is 'normal'.</p>")
                .append("</div>");
        } else {
            sb
                .append("<div class=\"failureBorder\">")
                .append("<p class=\"failureContent\">The analyzer startup failed with this message</p>")
                .append("<p class=\"failureContent\">").append(userAgentAnalyzerFailureMessage).append("</p>")
                .append("</div>");
        }
    }

    private void documentationBlock(StringBuilder sb, List<UserAgent> userAgents) {
        sb.append("<hr/>");
        sb.append("<p class=\"logobar documentation\">Read the online documentation at <a href=\"https://yauaa.basjes.nl\">" +
            "https://yauaa.basjes.nl</a></p>");
        sb.append("<p class=\"logobar bug\">");
        addBugReportButton(sb, userAgents.get(0));
        sb.append("</p>");
        sb.append("<p class=\"logobar swagger\">A simple Swagger based API has been created for testing purposes: " +
            "<a href=\"/swagger-ui/\">Swagger UI</a></p>");

        sb.append("<p class=\"logobar source\">This project is opensource: <a href=\"https://github.com/nielsbasjes/yauaa\">" +
            "https://github.com/nielsbasjes/yauaa</a></p>");
        sb.append("<p class=\"logobar contribute\">Creating this free software is a lot of work. " +
            "If this has business value for your then don't hesitate to " +
            "<a href=\"https://www.paypal.me/nielsbasjes\">contribute a little something back</a>.</p>");
    }

    private void htmlFooter(StringBuilder sb) {
        sb.append("<p class=\"speed\">").append(getMemoryUsage()).append("</p>");

        sb
            .append("<p class=\"copyright\">")
            .append(Version.COPYRIGHT.replace("Niels Basjes", "<a href=\"https://niels.basjes.nl\">Niels Basjes</a>"))
            .append("</p>")
            .append("</body>")
            .append("</html>");
    }

    private void htmlHead(StringBuilder sb) {
        sb.append("<!DOCTYPE html>");
        sb.append("<html lang=\"en\" ><head profile=\"http://www.w3.org/2005/10/profile\">");
        sb.append("<meta http-equiv='Content-Type' content='text/html; charset=UTF-8'>");
        sb.append("<meta name=viewport content=\"width=device-width, initial-scale=1\">");
        insertFavIcons(sb);
        sb.append("<meta name=\"theme-color\" content=\"dodgerblue\" />");

        // While initializing automatically reload the page.
        if (!userAgentAnalyzerIsAvailable() && getUserAgentAnalyzerFailureMessage() == null) {
            sb.append("<meta http-equiv=\"refresh\" content=\"1\" >");
        }
        sb.append("<link rel=\"stylesheet\" href=\"style.css\">");
        sb.append("<title>Analyzing the useragent</title>");

        sb.append("</head>");
        sb.append("<body>");
        sb.append("<div class=\"header\">");
        sb.append("<h1 class=\"title\">Yet Another UserAgent Analyzer.</h1>");
        sb.append("<p class=\"version\">").append(getVersion()).append(" (<a href=\"").append(GIT_REPO_URL).append("\">commit</a>)</p>");
        sb.append("</div>");
    }

    private void insertFavIcons(StringBuilder sb) {
        sb.append("<link rel=\"apple-touch-icon\" href=\"/apple-touch-icon.png\">");
        sb.append("<link rel=\"icon\" type=\"image/png\" sizes=\"32x32\" href=\"/favicon-32x32.png\">");
        sb.append("<link rel=\"icon\" type=\"image/png\" sizes=\"16x16\" href=\"/favicon-16x16.png\">");
        sb.append("<link rel=\"manifest\" href=\"/manifest.json\">");
        sb.append("<link rel=\"mask-icon\" href=\"/safari-pinned-tab.svg\" color=\"#5bbad5\">");
        sb.append("<meta name=\"msapplication-TileColor\" content=\"#2d89ef\">");
        sb.append("<meta name=\"msapplication-TileImage\" content=\"/mstile-144x144.png\">");
    }

    private Pair<String, String> prefixSplitter(String input) {
        MutablePair<String, String> result = new MutablePair<>("", input);
        if (input.startsWith("Device")) {
            result.setLeft("Device");
            result.setRight(input.replaceFirst("Device", ""));
        } else if (input.startsWith("OperatingSystem")) {
            result.setLeft("Operating System");
            result.setRight(input.replaceFirst("OperatingSystem", ""));
        } else if (input.startsWith("LayoutEngine")) {
            result.setLeft("Layout Engine");
            result.setRight(input.replaceFirst("LayoutEngine", ""));
        } else if (input.startsWith("Agent")) {
            result.setLeft("Agent");
            result.setRight(input.replaceFirst("Agent", ""));
        }
        return result;
    }

    private String camelStretcher(String input) {
        String result = input.replaceAll("([A-Z])", " $1");
        result = result.replace("Device", "<b><u>Device</u></b>");
        result = result.replace("Operating System", "<b><u>Operating System</u></b>");
        result = result.replace("Layout Engine", "<b><u>Layout Engine</u></b>");
        result = result.replace("Agent", "<b><u>Agent</u></b>");
        return result.trim();
    }


    private void addBugReportButton(StringBuilder sb, UserAgent userAgent) {
        // https://github.com/nielsbasjes/yauaa/issues/new?title=Bug%20report&body=bar

        try {
            StringBuilder reportUrl = new StringBuilder("https://github.com/nielsbasjes/yauaa/issues/new?title=Bug%20report&body=");

            String report = "I found a problem with this useragent.\n" +
                "[Please update the output below to match what you expect it should be]\n" +
                "\n```\n" +
                userAgent.toYamlTestCase().replaceAll(" +:", "  :") +
                "\n```\n";

            reportUrl.append(URLEncoder.encode(report, "UTF-8"));
            String githubUrl = "https://github.com/login?return_to=" + URLEncoder.encode(reportUrl.toString(), "UTF-8");
            sb.append("If you find a problem with this result then please report a bug here: " +
                "<a href=\"").append(githubUrl).append("\">Yauaa issue report</a>");
        } catch (UnsupportedEncodingException e) {
            // Never happens.
        }
    }
}

package org.matheclipse.repl;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.commons.io.output.StringBuilderWriter;
import org.apache.commons.text.StringEscapeUtils;
import org.matheclipse.core.basic.Config;
import org.matheclipse.core.basic.ToggleFeature;
import org.matheclipse.core.eval.EvalEngine;
import org.matheclipse.core.eval.ExprEvaluator;
import org.matheclipse.core.eval.GraphicsUtil;
import org.matheclipse.core.eval.MathMLUtilities;
import org.matheclipse.core.eval.TeXUtilities;
import org.matheclipse.core.eval.exception.AbortException;
import org.matheclipse.core.eval.exception.FailedException;
import org.matheclipse.core.eval.util.WriterOutputStream;
import org.matheclipse.core.expression.F;
import org.matheclipse.core.expression.S;
import org.matheclipse.core.expression.data.GraphExpr;
import org.matheclipse.core.form.output.JSBuilder;
import org.matheclipse.core.form.output.OutputFormFactory;
import org.matheclipse.core.graphics.GraphGraphics;
import org.matheclipse.core.graphics.WebGLGraphics3D;
import org.matheclipse.core.interfaces.IAST;
import org.matheclipse.core.interfaces.IExpr;
import org.matheclipse.core.interfaces.IStringX;
import org.matheclipse.core.parser.ExprParser;
import org.matheclipse.parser.client.ParserConfig;
import org.matheclipse.parser.client.SyntaxError;
import org.matheclipse.parser.client.math.MathException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;

/**
 * CheerpJ / WASM bridge that replaces the HTTP POST to {@code /ajax/query/} of
 * {@code AJAXQueryServlet}. Exposes static {@link #evaluate(String)} entry points that JavaScript
 * invokes through CheerpJ.
 */
public class ServletWASM {

  protected static final String VISJS_IFRAME = //
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + "\n" + "<!DOCTYPE html PUBLIC\n"
          + "  \"-//W3C//DTD XHTML 1.1 plus MathML 2.0 plus SVG 1.1//EN\"\n"
          + "  \"http://www.w3.org/2002/04/xhtml-math-svg/xhtml-math-svg.dtd\">\n" + "\n"
          + "<html xmlns=\"http://www.w3.org/1999/xhtml\" style=\"width: 100%; height: 100%; margin: 0; padding: 0\">\n"
          + "<head>\n" + "<meta charset=\"utf-8\">\n" + "<title>VIS-NetWork</title>\n" + "\n"
          + "  <script type=\"text/javascript\" src=\"https://cdn.jsdelivr.net/npm/vis-network@6.0.0/dist/vis-network.min.js\"></script>\n"
          + "</head>\n" + "<body>\n" + "\n"
          + "<div id=\"vis\" style=\"width: 600px; height: 400px; margin: 0;  padding: .25in .5in .5in .5in; flex-direction: column; overflow: hidden\">\n"
          + "<script type=\"text/javascript\">\n" + "`1`\n"
          + "  var container = document.getElementById('vis');\n" + "  var data = {\n"
          + "    nodes: nodes,\n" + "    edges: edges\n" + "  };\n" + "`2`\n"
          + "  var network = new vis.Network(container, data, options);\n" + "</script>\n"
          + "</div>\n" + "</body>\n" + "</html>";

  protected static final String HTML_IFRAME = //
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + "\n" + "<!DOCTYPE html PUBLIC\n"
          + "  \"-//W3C//DTD XHTML 1.1 plus MathML 2.0 plus SVG 1.1//EN\"\n"
          + "  \"http://www.w3.org/2002/04/xhtml-math-svg/xhtml-math-svg.dtd\">\n" + "\n"
          + "<html xmlns=\"http://www.w3.org/1999/xhtml\" style=\"width: 100%; height: 100%; margin: 0; padding: 0\">\n"
          + "<head>\n" + "<meta charset=\"utf-8\">\n" + "<title>HTML</title>\n" + "</head>\n"
          + "<body>\n" + "`1`\n" + "</body>\n" + "</html>";

  private static volatile boolean INITIALIZED = false;
  private static ExprEvaluator evaluator;

  static {
    initialization();
  }

  /** One-shot global initialization mirroring {@code AJAXQueryServlet#initialization()}. */
  public static synchronized void initialization() {
    if (INITIALIZED) {
      return;
    }
    INITIALIZED = true;
    ParserConfig.PARSER_USE_LOWERCASE_SYMBOLS = true;
    ToggleFeature.COMPILE = true;
    ToggleFeature.COMPILE_PRINT = true;
    Config.UNPROTECT_ALLOWED = false;
    Config.USE_MANIPULATE_JS = true;
    Config.JAS_NO_THREADS = false;
    Config.JAVA_UNSAFE = true;
    Config.MATHML_TRIG_LOWERCASE = false;
    Config.DEFAULT_ITERATION_LIMIT = 10_000;
    Config.DEFAULT_RECURSION_LIMIT = 1_024;
    Config.FILESYSTEM_ENABLED = false;

    F.initSymja();
    EvalEngine engine = new EvalEngine(true);
    EvalEngine.set(engine);
    engine.setRecursionLimit(Config.DEFAULT_RECURSION_LIMIT);
    engine.setIterationLimit(Config.DEFAULT_ITERATION_LIMIT);

    // Register graphics evaluators so Plot/Plot3D produce SVG/WebGL output.
    try {
      S.Plot.setEvaluator(org.matheclipse.core.builtin.graphics.Plot.CONST);
      S.Plot3D.setEvaluator(org.matheclipse.core.builtin.graphics3d.Plot3D.CONST);
    } catch (Throwable t) {
      // best-effort: continue without graphics evaluators
    }

    evaluator = new ExprEvaluator(engine, false, (short) 100);
    System.out.println("Symja WASM version " + Config.VERSION + " initialized");
  }

  /** Default entry point: no numeric-mode, no output-format function. */
  public static String evaluate(String input) {
    return evaluate(input, "", "");
  }

  /**
   * Evaluate the given Symja expression and produce the same JSON envelope as
   * {@code AJAXQueryServlet} returns for an AJAX POST.
   *
   * @param input the math expression entered in the textarea
   * @param numericMode {@code "N"} to wrap the result in {@code N[...]}, otherwise empty
   * @param function {@code "$mathml"} or {@code "$tex"} to force a specific output format,
   *        otherwise empty
   * @return JSON string compatible with {@code setResult()} in {@code symja.js}
   */
  public static String evaluate(String input, String numericMode, String function) {
    if (input == null || input.trim().length() == 0) {
      return JSONBuilder.createJSONErrorString("No input expression posted!");
    }
    if (input.length() >= Short.MAX_VALUE) {
      return JSONBuilder.createJSONErrorString(
          "Input expression greater than: " + Short.MAX_VALUE + " characters!");
    }
    if (numericMode == null) {
      numericMode = "";
    }
    if (function == null) {
      function = "";
    }

    final StringBuilderWriter outWriter = new StringBuilderWriter();
    final StringBuilderWriter errorWriter = new StringBuilderWriter();
    try (PrintStream outs = new PrintStream(new WriterOutputStream(outWriter));
        PrintStream errors = new PrintStream(new WriterOutputStream(errorWriter))) {

      EvalEngine engine = evaluator.getEvalEngine();
      engine.setOutPrintStream(outs);
      engine.setErrorPrintStream(errors);

      String[] result = evaluateString(engine, input.trim(), numericMode, function, outWriter,
          errorWriter);
      if (result == null) {
        return JSONBuilder.createJSONError("Calculation result is undefined")[1];
      }
      return result[1];
    } catch (Exception e) {
      String msg = e.getMessage();
      if (msg == null) {
        msg = e.getClass().getSimpleName();
      }
      return JSONBuilder.createJSONErrorString("WASM Error: " + msg);
    }
  }

  private static String[] evaluateString(EvalEngine engine, final String input,
      final String numericMode, final String function, StringBuilderWriter outWriter,
      StringBuilderWriter errorWriter) {
    try {
      EvalEngine.setReset(engine);
      ExprParser parser = new ExprParser(engine, true);
      IExpr inExpr = parser.parse(input);
      if (inExpr == null) {
        return JSONBuilder.createJSONError("Input string parsed to null");
      }
      long numberOfLeaves = inExpr.leafCount();
      if (numberOfLeaves > Config.MAX_INPUT_LEAVES) {
        return JSONBuilder.createJSONError("Input expression too big!");
      }
      if ("N".equals(numericMode)) {
        inExpr = F.N(inExpr);
      }

      StringBuilderWriter outBuffer = new StringBuilderWriter();
      IExpr outExpr = evalTopLevel(engine, outBuffer, inExpr);
      if (outExpr == null) {
        return createOutput(outBuffer, engine, function);
      }

      // -- GraphExpr -> 2D Graphics
      if (outExpr instanceof GraphExpr) {
        try {
          GraphGraphics graphGraphics = new GraphGraphics(outExpr);
          IAST graphics = graphGraphics.toGraphics();
          if (graphics.isPresent()) {
            outExpr = graphics;
          }
        } catch (Throwable t) {
          // fall through with original outExpr
        }
      }

      // -- Graphics -> SVG
      if (outExpr.isGraphicsObject()) {
        StringBuilder buf = new StringBuilder();
        if (GraphicsUtil.renderGraphics2DSVG(buf, (IAST) outExpr, engine)) {
          String svg = buf.toString();
          return JSONBuilder.createJSONJavaScript(
              "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"600\" height=\"400\" style=\"max-width: 100%; height: auto;\" viewBox=\"0 0 600 400\">"
                  + svg + "</svg>");
        }
      } else if (outExpr.isASTSizeGE(S.Graphics3D, 2)) {
        String webglSnippet = WebGLGraphics3D.generateHTMLSnippet((IAST) outExpr);
        return JSONBuilder.createJSONJavaScript(webglSnippet);
      }

      // -- Show
      if (outExpr.isASTSizeGE(S.Show, 2)) {
        IAST show = (IAST) outExpr;
        return JSONBuilder.createJSONShow(engine, show);
      }

      // -- GraphExpr (network rendering via vis-network)
      if (outExpr instanceof GraphExpr) {
        String javaScriptStr = ((GraphExpr) outExpr).graphToJSForm();
        if (javaScriptStr != null) {
          String html = VISJS_IFRAME;
          html = html.replace("`1`", javaScriptStr);
          html = html.replace("`2`", "  var options = { };\n");
          html = StringEscapeUtils.escapeHtml4(html);
          return JSONBuilder.createJSONJavaScript("<iframe srcdoc=\"" + html
              + "\" style=\"display: block; width: 100%; height: 100%; border: none;\" ></iframe>");
        }
      }

      // -- JSFormData (mathcell, echarts, jsxgraph, mermaid, plotly, treeform)
      if (outExpr.isAST(S.JSFormData, 3)) {
        IAST jsFormData = (IAST) outExpr;
        String jsLibraryType = jsFormData.arg2().toString();
        String payload = jsFormData.arg1().toString();
        try {
          if (JSBuilder.MATHCELL_STR.equals(jsLibraryType)) {
            return JSONBuilder.createMathcellIFrame(JSBuilder.MATHCELL_IFRAME_TEMPLATE, payload);
          } else if (JSBuilder.ECHARTS_STR.equals(jsLibraryType)) {
            return JSONBuilder.createEChartsIFrame(JSBuilder.ECHARTS_IFRAME_TEMPLATE, payload);
          } else if (JSBuilder.JSXGRAPH_STR.equals(jsLibraryType)) {
            return JSONBuilder.createJSXGraphIFrame(JSBuilder.JSXGRAPH_IFRAME_TEMPLATE, payload);
          } else if (JSBuilder.MERMAID_STR.equals(jsLibraryType)) {
            return JSONBuilder.createMermaidIFrame(JSBuilder.MERMAID_IFRAME_TEMPLATE, payload);
          } else if (JSBuilder.PLOTLY_STR.equals(jsLibraryType)) {
            return JSONBuilder.createPlotlyIFrame(JSBuilder.PLOTLY_IFRAME_TEMPLATE, payload);
          } else if (JSBuilder.TREEFORM_STR.equals(jsLibraryType)) {
            String html = VISJS_IFRAME;
            html = html.replace("`1`", payload);
            html = html.replace("`2`", //
                "  var options = {\n" + "          edges: {\n" + "              smooth: {\n"
                    + "                  type: 'cubicBezier',\n"
                    + "                  forceDirection:  'vertical',\n"
                    + "                  roundness: 0.4\n" + "              }\n" + "          },\n"
                    + "          layout: {\n" + "              hierarchical: {\n"
                    + "                  direction: \"UD\"\n" + "              }\n"
                    + "          },\n" + "          nodes: {\n" + "            shape: 'box'\n"
                    + "          },\n" + "          physics:false\n" + "      }; ");
            html = StringEscapeUtils.escapeHtml4(html);
            return JSONBuilder.createJSONJavaScript("<iframe srcdoc=\"" + html
                + "\" style=\"display: block; width: 100%; height: 100%; border: none;\" ></iframe>");
          }
        } catch (Exception ex) {
          // fall through to default rendering
        }
      }

      // -- HTML string (mime text/html)
      if (outExpr.isString()) {
        IStringX str = (IStringX) outExpr;
        if (str.getMimeType() == IStringX.TEXT_HTML) {
          String htmlSnippet = str.toString();
          String htmlPage = HTML_IFRAME.replace("`1`", htmlSnippet);
          return JSONBuilder.createJSONJavaScript("<iframe srcdoc=\"" + htmlPage
              + "\" style=\"display: block; width: 100%; height: 100%; border: none;\" ></iframe>");
        }
      }

      // -- Forced MathML / TeX via function parameter
      if ("$mathml".equals(function) || "$tex".equals(function)) {
        return createOutput(outBuffer, engine, function);
      }

      return JSONBuilder.createJSONResult(engine, outExpr, outWriter, errorWriter);
    } catch (AbortException se) {
      return JSONBuilder.createJSONResult(engine, S.$Aborted, outWriter, errorWriter);
    } catch (FailedException se) {
      return JSONBuilder.createJSONResult(engine, S.$Failed, outWriter, errorWriter);
    } catch (SyntaxError se) {
      return JSONBuilder.createJSONSyntaxError(se.getMessage());
    } catch (MathException se) {
      return JSONBuilder.createJSONError(se.getMessage());
    } catch (Exception e) {
      String msg = e.getMessage();
      return JSONBuilder.createJSONError(msg != null ? "Error in evaluateString: " + msg
          : "Error in evaluateString: " + e.getClass().getSimpleName());
    }
  }

  private static IExpr evalTopLevel(EvalEngine engine, final StringBuilderWriter buf,
      final IExpr parsedExpression) {
    EvalEngine[] engineRef = new EvalEngine[] {engine};
    IExpr result = ExprEvaluator.evalTopLevel(parsedExpression, engineRef);
    EvalEngine resolved = engineRef[0];
    if ((result != null) && !result.equals(S.Null)) {
      OutputFormFactory.get(resolved.isRelaxedSyntax()).convert(buf, result);
    }
    return result;
  }

  private static String[] createOutput(StringBuilderWriter buffer, EvalEngine engine,
      String function) {
    String res = buffer.toString();
    if ("$mathml".equals(function)) {
      MathMLUtilities mathMLUtil = new MathMLUtilities(engine, false, true);
      StringBuilderWriter stw = new StringBuilderWriter();
      if (!mathMLUtil.toMathML(res, stw, true)) {
        return new String[] {"error", "Max. output size exceeded " + Config.MAX_OUTPUT_SIZE};
      }
      return new String[] {"mathml", stw.toString()};
    } else if ("$tex".equals(function)) {
      TeXUtilities texUtil = new TeXUtilities(engine, true);
      StringBuilderWriter stw = new StringBuilderWriter();
      if (!texUtil.toTeX(res, stw, false)) {
        return new String[] {"error", "Max. output size exceeded " + Config.MAX_OUTPUT_SIZE};
      }
      return new String[] {"tex", stw.toString()};
    }
    return new String[] {"expr", res};
  }

  // ---------------------------------------------------------------------------
  // Jupyter notebook (.ipynb / nbformat v4) save & load support
  // ---------------------------------------------------------------------------

  private static final ObjectMapper IPYNB_MAPPER = new ObjectMapper();

  private static final Pattern SVG_PATTERN =
      Pattern.compile("<svg\\b[^>]*>[\\s\\S]*?</svg>", Pattern.CASE_INSENSITIVE);

  private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

  /**
   * Convert the SymjaCheerpJ worksheet JSON (an array of {@code {request, results}} objects as
   * produced by {@code getContent()} in {@code inout.js}) into a Jupyter {@code nbformat} v4
   * notebook string.
   *
   * <p>Each worksheet item becomes a single {@code code} cell whose {@code source} is the user
   * input. Per-result objects are translated to {@code execute_result} outputs containing as many
   * representations as possible ({@code text/plain}, {@code text/html},
   * {@code application/mathml+xml}, {@code image/svg+xml} and {@code image/png}) so that the
   * notebook renders both in SymjaCheerpJ and in plain Jupyter / JupyterLab.
   *
   * @param worksheetJson JSON array as produced by SymjaCheerpJ's {@code getContent()}
   * @return a Jupyter {@code nbformat} v4 JSON document
   */
  public static String toIpynb(String worksheetJson) {
    try {
      JsonNode root = IPYNB_MAPPER.readTree(worksheetJson == null ? "[]" : worksheetJson);
      ObjectNode notebook = IPYNB_MAPPER.createObjectNode();
      ArrayNode cells = IPYNB_MAPPER.createArrayNode();

      int execCounter = 0;
      if (root.isArray()) {
        for (JsonNode item : root) {
          ObjectNode cell = IPYNB_MAPPER.createObjectNode();
          cell.put("cell_type", "code");
          String request = item.path("request").asText("");
          execCounter++;
          cell.put("execution_count", execCounter);
          cell.set("metadata", IPYNB_MAPPER.createObjectNode());
          cell.set("source", splitSource(request));

          ArrayNode outputs = IPYNB_MAPPER.createArrayNode();
          JsonNode results = item.path("results");
          if (results.isArray()) {
            for (JsonNode result : results) {
              JsonNode outArr = result.path("out");
              if (outArr.isArray()) {
                for (JsonNode out : outArr) {
                  String text = out.path("text").asText("");
                  if (out.path("message").asBoolean(false)) {
                    ObjectNode err = IPYNB_MAPPER.createObjectNode();
                    err.put("output_type", "error");
                    err.put("ename", out.path("prefix").asText("Error"));
                    err.put("evalue", stripTags(text));
                    ArrayNode tb = IPYNB_MAPPER.createArrayNode();
                    tb.add(stripTags(text));
                    err.set("traceback", tb);
                    outputs.add(err);
                  } else if (!text.isEmpty()) {
                    ObjectNode stream = IPYNB_MAPPER.createObjectNode();
                    stream.put("output_type", "stream");
                    stream.put("name", "stdout");
                    stream.set("text", splitSource(stripTags(text)));
                    outputs.add(stream);
                  }
                }
              }
              JsonNode resultNode = result.path("result");
              if (!resultNode.isNull() && !resultNode.isMissingNode()) {
                String html = resultNode.asText("");
                if (!html.isEmpty()) {
                  ObjectNode execOut = IPYNB_MAPPER.createObjectNode();
                  execOut.put("output_type", "execute_result");
                  execOut.put("execution_count", execCounter);
                  ObjectNode data = IPYNB_MAPPER.createObjectNode();
                  data.set("text/plain", splitSource(stripTags(html)));
                  data.set("text/html", splitSource(html));
                  if (html.contains("<math")) {
                    data.set("application/mathml+xml", splitSource(html));
                  }
                  String svg = extractSvg(html);
                  if (svg != null) {
                    data.set("image/svg+xml", splitSource(svg));
                    String png = svgToPngBase64(svg);
                    if (png != null) {
                      data.put("image/png", png);
                    }
                  }
                  execOut.set("data", data);
                  execOut.set("metadata", IPYNB_MAPPER.createObjectNode());
                  outputs.add(execOut);
                }
              }
            }
          }
          cell.set("outputs", outputs);
          cells.add(cell);
        }
      }

      notebook.set("cells", cells);
      ObjectNode metadata = IPYNB_MAPPER.createObjectNode();
      ObjectNode kernelspec = IPYNB_MAPPER.createObjectNode();
      kernelspec.put("name", "symja");
      kernelspec.put("display_name", "Symja");
      kernelspec.put("language", "mathematica");
      metadata.set("kernelspec", kernelspec);
      ObjectNode lang = IPYNB_MAPPER.createObjectNode();
      lang.put("name", "mathematica");
      lang.put("file_extension", ".m");
      lang.put("mimetype", "application/vnd.wolfram.mathematica");
      lang.put("pygments_lexer", "mathematica");
      metadata.set("language_info", lang);
      ObjectNode symjaMeta = IPYNB_MAPPER.createObjectNode();
      symjaMeta.put("version", Config.VERSION);
      metadata.set("symja", symjaMeta);
      notebook.set("metadata", metadata);
      notebook.put("nbformat", 4);
      notebook.put("nbformat_minor", 5);

      return IPYNB_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(notebook);
    } catch (Exception e) {
      String msg = e.getMessage();
      return JSONBuilder.createJSONErrorString(
          "toIpynb failed: " + (msg != null ? msg : e.getClass().getSimpleName()));
    }
  }

  /**
   * Inverse of {@link #toIpynb(String)}. Reads a Jupyter {@code nbformat} v4 notebook and rebuilds
   * the SymjaCheerpJ worksheet JSON consumed by {@code setContent()} in {@code inout.js}.
   *
   * <p>Only {@code code} cells are translated. Previously saved outputs ({@code text/html},
   * {@code image/svg+xml}, {@code text/plain}) are restored into the {@code result} field of a
   * synthetic result object, and {@code stream}/{@code error} outputs are mapped back into the
   * {@code out} array so {@code setResult()} can re-render them without re-evaluating the cell.
   *
   * @param ipynbJson a Jupyter {@code nbformat} v4 JSON document
   * @return JSON array string compatible with SymjaCheerpJ's {@code setContent()}
   */
  public static String fromIpynb(String ipynbJson) {
    try {
      JsonNode notebook = IPYNB_MAPPER.readTree(ipynbJson == null ? "{}" : ipynbJson);
      ArrayNode worksheet = IPYNB_MAPPER.createArrayNode();
      JsonNode cells = notebook.path("cells");
      if (cells.isArray()) {
        for (JsonNode cell : cells) {
          if (!"code".equals(cell.path("cell_type").asText(""))) {
            continue;
          }
          ObjectNode item = IPYNB_MAPPER.createObjectNode();
          item.put("request", joinSource(cell.path("source")));

          ArrayNode results = IPYNB_MAPPER.createArrayNode();
          ObjectNode result = IPYNB_MAPPER.createObjectNode();
          ArrayNode outs = IPYNB_MAPPER.createArrayNode();
          result.putNull("line");
          result.putNull("result");

          JsonNode outputs = cell.path("outputs");
          if (outputs.isArray()) {
            for (JsonNode output : outputs) {
              String type = output.path("output_type").asText("");
              if ("stream".equals(type)) {
                ObjectNode out = IPYNB_MAPPER.createObjectNode();
                out.put("prefix", output.path("name").asText("stdout"));
                out.put("message", false);
                out.put("text", joinSource(output.path("text")));
                outs.add(out);
              } else if ("error".equals(type)) {
                ObjectNode out = IPYNB_MAPPER.createObjectNode();
                out.put("prefix", output.path("ename").asText("Error"));
                out.put("message", true);
                String text = output.path("evalue").asText("");
                if (text.isEmpty()) {
                  text = joinSource(output.path("traceback"));
                }
                out.put("text", text);
                outs.add(out);
              } else if ("execute_result".equals(type) || "display_data".equals(type)) {
                JsonNode data = output.path("data");
                String rendered = pickRendering(data);
                if (rendered != null && result.path("result").isNull()) {
                  result.put("result", rendered);
                } else if (rendered != null) {
                  // additional execute_result -> push previous result, start new one
                  result.set("out", outs);
                  results.add(result);
                  result = IPYNB_MAPPER.createObjectNode();
                  outs = IPYNB_MAPPER.createArrayNode();
                  result.putNull("line");
                  result.put("result", rendered);
                }
              }
            }
          }
          result.set("out", outs);
          results.add(result);
          item.set("results", results);
          worksheet.add(item);
        }
      }
      return IPYNB_MAPPER.writeValueAsString(worksheet);
    } catch (Exception e) {
      String msg = e.getMessage();
      return JSONBuilder.createJSONErrorString(
          "fromIpynb failed: " + (msg != null ? msg : e.getClass().getSimpleName()));
    }
  }

  /** Split a string into an array of lines (each line keeps its trailing {@code \n}). */
  private static ArrayNode splitSource(String text) {
    ArrayNode arr = IPYNB_MAPPER.createArrayNode();
    if (text == null || text.isEmpty()) {
      return arr;
    }
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      arr.add(i < lines.length - 1 ? lines[i] + "\n" : lines[i]);
    }
    return arr;
  }

  /** Join a Jupyter "multiline" field that may be either a string or an array of strings. */
  private static String joinSource(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "";
    }
    if (node.isTextual()) {
      return node.asText();
    }
    if (node.isArray()) {
      StringBuilder sb = new StringBuilder();
      for (Iterator<JsonNode> it = node.elements(); it.hasNext();) {
        sb.append(it.next().asText(""));
      }
      return sb.toString();
    }
    return node.asText("");
  }

  /** Strip HTML/MathML tags for a {@code text/plain} fallback. */
  private static String stripTags(String html) {
    if (html == null) {
      return "";
    }
    String noTags = TAG_PATTERN.matcher(html).replaceAll("");
    return StringEscapeUtils.unescapeHtml4(noTags).trim();
  }

  /** Pull the first {@code <svg ...>...</svg>} fragment out of a rendered HTML string. */
  private static String extractSvg(String html) {
    if (html == null) {
      return null;
    }
    Matcher m = SVG_PATTERN.matcher(html);
    return m.find() ? m.group() : null;
  }

  /** Pick the best representation to round-trip into SymjaCheerpJ's {@code result} field. */
  private static String pickRendering(JsonNode data) {
    if (data == null || data.isMissingNode() || data.isNull()) {
      return null;
    }
    if (data.hasNonNull("text/html")) {
      return joinSource(data.get("text/html"));
    }
    if (data.hasNonNull("application/mathml+xml")) {
      return joinSource(data.get("application/mathml+xml"));
    }
    if (data.hasNonNull("image/svg+xml")) {
      return joinSource(data.get("image/svg+xml"));
    }
    if (data.hasNonNull("image/png")) {
      return "<img src=\"data:image/png;base64," + data.get("image/png").asText() + "\" />";
    }
    if (data.hasNonNull("text/plain")) {
      return joinSource(data.get("text/plain"));
    }
    return null;
  }

  /**
   * Rasterise the supplied SVG fragment into a base64-encoded PNG using JSVG. Returns {@code null}
   * if rendering fails (e.g. unsupported SVG construct) so callers can simply omit the
   * {@code image/png} key.
   */
  private static String svgToPngBase64(String svg) {
    try {
      SVGLoader loader = new SVGLoader();
      SVGDocument document = loader.load(
          new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)), null,
          LoaderContext.createDefault());
      if (document == null) {
        return null;
      }
      float width = document.size().width;
      float height = document.size().height;
      if (!(width > 0) || !(height > 0)) {
        width = 600f;
        height = 400f;
      }
      int w = Math.max(1, Math.round(width));
      int h = Math.max(1, Math.round(height));
      BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = image.createGraphics();
      try {
        document.render(null, g, new ViewBox(0, 0, w, h));
      } finally {
        g.dispose();
      }
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      if (!ImageIO.write(image, "png", baos)) {
        return null;
      }
      return Base64.getEncoder().encodeToString(baos.toByteArray());
    } catch (Throwable t) {
      return null;
    }
  }
}

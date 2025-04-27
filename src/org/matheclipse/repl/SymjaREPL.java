package org.matheclipse.repl;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List; // Needed for process()
import javax.script.ScriptException;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.matheclipse.core.basic.Config;
import org.matheclipse.core.basic.ToggleFeature;
import org.matheclipse.core.eval.EvalEngine;
import org.matheclipse.core.eval.ExprEvaluator;
import org.matheclipse.core.expression.F;
import org.matheclipse.core.expression.S;
import org.matheclipse.core.form.Documentation;
import org.matheclipse.core.interfaces.IExpr;
import org.matheclipse.gpl.numbertheory.BigIntegerPrimality;
import org.matheclipse.image.builtin.ImageFunctions;
import org.matheclipse.parser.client.ParserConfig;
import org.matheclipse.parser.client.SyntaxError;
import org.matheclipse.parser.client.math.MathException;

public class SymjaREPL extends JFrame implements ActionListener {

  private final JTextArea outputArea;
  private final JTextField inputField;
  private final JScrollPane scrollPane;
  private final JButton evalButton;
  private final JButton clearButton;
  private final JLabel statusLabel;
  private ExprEvaluator evaluator;

  public static void configureLog4J() {
    System.setProperty("log4j.hostName", "unknown");
    System.setProperty("log4j2.disable.jmx", "true");
    // Optionally, reconfigure Log4j if it's already initialized
    // Configurator.reconfigure();
  }

  static {
    configureLog4J();
    ToggleFeature.COMPILE = true;
    ToggleFeature.COMPILE_PRINT = true;
    ParserConfig.PARSER_USE_LOWERCASE_SYMBOLS = false;
    Config.DISABLE_JMX = true;
    Config.SHORTEN_STRING_LENGTH = 80;
    Config.MAX_AST_SIZE = 20000;
    Config.MAX_MATRIX_DIMENSION_SIZE = 100;
    Config.MAX_BIT_LENGTH = 200000;
    Config.MAX_POLYNOMIAL_DEGREE = 100;
    Config.FILESYSTEM_ENABLED = true;
    Config.JAS_NO_THREADS = true;
    try {
      F.await();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    // set for BigInteger prime factorization
    Config.PRIME_FACTORS = new BigIntegerPrimality();

    // initialize from module matheclipse-image:
    ImageFunctions.initialize();

    // S.ArrayPlot.setEvaluator(new org.matheclipse.image.builtin.ArrayPlot());
    S.ImageCrop.setEvaluator(new org.matheclipse.image.builtin.ImageCrop());
    S.ListDensityPlot.setEvaluator(new org.matheclipse.image.builtin.ListDensityPlot());
    S.ListLogPlot.setEvaluator(new org.matheclipse.image.builtin.ListLogPlot());
    S.ListLogLogPlot.setEvaluator(new org.matheclipse.image.builtin.ListLogLogPlot());

    // S.ListPlot.setEvaluator(new org.matheclipse.image.bridge.fig.ListPlot());
    // S.Histogram.setEvaluator(new org.matheclipse.image.bridge.fig.Histogram());
    // S.Plot.setEvaluator(new org.matheclipse.image.bridge.fig.Plot());
  }

  public SymjaREPL() {
    super("SymjaREPL");

    boolean relaxedSyntax = false;
    EvalEngine engine = new EvalEngine(relaxedSyntax);
    EvalEngine.set(engine);
    engine.init();
    engine.setRecursionLimit(512);
    engine.setIterationLimit(500);
    engine.setOutListDisabled(false, (short) 10);

    evaluator = new ExprEvaluator(engine, false, (short) 100);

    // 2. Setup Components
    outputArea = new JTextArea();
    outputArea.setEditable(false);
    outputArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
    outputArea.setLineWrap(true);
    outputArea.setWrapStyleWord(true);

    scrollPane = new JScrollPane(outputArea);
    scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

    inputField = new JTextField(80);
    inputField.setFont(new Font("Monospaced", Font.PLAIN, 12));
    // Optional: Allow Enter key in text field to trigger button
    inputField.addActionListener(this);

    evalButton = new JButton("Eval");
    evalButton.addActionListener(this);
    clearButton = new JButton("Clear");
    clearButton.addActionListener(this);

    statusLabel = new JLabel("Ready.");

    // 3. Layout

    // --- Panel for the Row ---
    JPanel rowPanel = new JPanel();
    rowPanel.setLayout(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();

    // --- Add Input Field ---
    gbc.gridx = 0;
    gbc.gridy = 0;
    // gbc.weightx = 1.0; // This component gets all extra horizontal space
    gbc.fill = GridBagConstraints.HORIZONTAL; // Expand the component horizontally to fill its cell
    gbc.insets = new Insets(5, 5, 5, 5); // Add padding (top, left, bottom, right) around the
                                         // component
    rowPanel.add(inputField, gbc);

    // --- Add Button 1 ---
    gbc = new GridBagConstraints();
    gbc.gridx = 1;
    gbc.gridy = 0;
    gbc.weightx = 0.0;
    gbc.fill = GridBagConstraints.NONE; // Do not expand the component
    gbc.insets = new Insets(5, 0, 5, 5); // Padding: top=5, left=0 (less space next to text field),
                                         // bottom=5, right=5
    // gbc.anchor = GridBagConstraints.WEST; // Optional: Align button to the left within its cell
    // if cell is larger
    rowPanel.add(clearButton, gbc);

    gbc = new GridBagConstraints();
    gbc.gridx = 2;
    gbc.gridy = 0;
    gbc.weightx = 0.0; // Does not get extra horizontal space
    gbc.fill = GridBagConstraints.NONE; // Do not expand
    gbc.insets = new Insets(5, 0, 5, 5); // Padding: top=5, left=0, bottom=5, right=5
    // gbc.anchor = GridBagConstraints.WEST; // Optional
    rowPanel.add(evalButton, gbc);

    JPanel bottomPanel = new JPanel(new BorderLayout());
    // bottomPanel.add(inputField, BorderLayout.CENTER);
    // bottomPanel.add(evalButton, BorderLayout.EAST);
    bottomPanel.add(rowPanel, BorderLayout.EAST);
    bottomPanel.add(statusLabel, BorderLayout.SOUTH); // Add status label

    setLayout(new BorderLayout());
    add(scrollPane, BorderLayout.CENTER);
    add(bottomPanel, BorderLayout.SOUTH);

    // 4. Frame Setup
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    setSize(600, 450); // Slightly taller for status
    setLocationRelativeTo(null);

    // Initial message
    appendToOutput("Welcome to Symja REPL!\n");
    appendToOutput("Type commands and press Enter or click Evaluate.\n");
    appendToOutput("--------------------------------------------------\n");
  }

  // Method to append text safely to the output area from any thread
  private void appendToOutput(String text) {
    SwingUtilities.invokeLater(() -> {
      outputArea.append(text);
      outputArea.setCaretPosition(outputArea.getDocument().getLength());
    });
  }

  // Update status label safely from any thread
  private void setStatus(String status) {
    SwingUtilities.invokeLater(() -> statusLabel.setText(status));
  }


  // --- ActionListener Implementation ---
  @Override
  public void actionPerformed(ActionEvent e) {
    String actionCommand = e.getActionCommand();
    if (actionCommand.equals("Clear")) {
      inputField.setText("");
      inputField.requestFocusInWindow();
      return;
    }
    String command = inputField.getText();
    if (command.trim().isEmpty()) {
      return;
    }

    appendToOutput("\n>> " + command + "\n");

    if (evaluator != null) {
      // Disable UI elements during processing
      inputField.setEnabled(false);
      evalButton.setEnabled(false);
      setStatus("Evaluating...");

      // Create and execute the SwingWorker
      EvaluationWorker worker = new EvaluationWorker(command);
      worker.execute();
    } else {
      appendToOutput("ECHO: " + command + " (No script engine available)\n");
    }
  }


  // --- The SwingWorker Implementation ---
  private class EvaluationWorker extends SwingWorker<Object, String> {
    private final String commandToEvaluate;

    public EvaluationWorker(String command) {
      this.commandToEvaluate = command;
    }

    @Override
    protected IExpr doInBackground() throws Exception {
      // This runs on a background thread - DO NOT touch Swing components directly
      try {
        if (commandToEvaluate.startsWith("?")) {
          EvalEngine.setReset(evaluator.getEvalEngine());
          // print "Usage" or built-in help
          String name = commandToEvaluate.trim();
          IExpr doc = Documentation.findDocumentation(name);
          appendToOutput(doc.toString() + "\n\n");
          return doc;
        }
        IExpr rv = evaluator.eval(commandToEvaluate);
        if (Desktop.isDesktopSupported()) {
          IExpr outExpr = rv;
          if (rv.isAST(S.Graphics) //
              || rv.isAST(F.Graphics3D)) {
            outExpr = F.Show(outExpr);
          }
          String html = F.show(outExpr);
        }
        return rv;
      } catch (SyntaxError se) {
        // catch Symja parser errors here
        se.printStackTrace();
      } catch (MathException me) {
        // catch Symja math errors here
        me.printStackTrace();
      } catch (final Exception ex) {
        ex.printStackTrace();
      } catch (final StackOverflowError soe) {
        soe.printStackTrace();
      } catch (final OutOfMemoryError oome) {
        oome.printStackTrace();
      }
      return S.Null;
    }

    // Optional: Process intermediate results published from doInBackground
    @Override
    protected void process(List<String> chunks) {
      // This runs on the EDT
      // for (String status : chunks) {
        // Example: Update a progress label or bar
        // statusLabel.setText(status); // Could use this for finer grained progress
      // }
    }


    @Override
    protected void done() {
      // This runs on the EDT after doInBackground finishes
      try {
        // Retrieve the result from doInBackground()
        // This will re-throw any exception that occurred in doInBackground
        Object result = get();

        if (result != null) {
          appendToOutput(result.toString() + "\n");
        } else {
          appendToOutput("null (evaluation returned null)\n");
        }
        setStatus("Evaluation finished successfully.");

      } catch (Exception e) {
        // Handle exceptions that occurred in doInBackground
        Throwable cause = e.getCause(); // get() wraps exceptions in ExecutionException
        String errorMessage;
        if (cause instanceof ScriptException) {
          errorMessage = "Script Error: " + cause.getMessage();
        } else if (cause != null) {
          errorMessage = "Error: " + cause.getClass().getSimpleName() + " - " + cause.getMessage();
        } else {
          errorMessage = "Execution Error: " + e.getMessage();
        }
        appendToOutput(errorMessage + "\n");
        setStatus("Evaluation failed.");
        // You might want to print the stack trace to console for debugging
        e.printStackTrace();
      } finally {
        // Re-enable UI elements regardless of success or failure
        inputField.setEnabled(true);
        evalButton.setEnabled(true);
        inputField.requestFocusInWindow();
      }
    }
  }


  // --- Main Method ---
  public static void main(String[] args) {
    SwingUtilities.invokeLater(() -> {
      SymjaREPL repl = new SymjaREPL();
      repl.setVisible(true);
    });
  }
}

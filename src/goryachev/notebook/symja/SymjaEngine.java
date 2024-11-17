// Copyright © 2015-2023 Andy Goryachev <andy@goryachev.com>
package goryachev.notebook.symja;

import java.util.concurrent.atomic.AtomicInteger;
import org.matheclipse.core.basic.Config;
import org.matheclipse.core.basic.ToggleFeature;
import org.matheclipse.core.eval.EvalEngine;
import org.matheclipse.core.eval.ExprEvaluator;
import org.matheclipse.core.expression.F;
import org.matheclipse.core.expression.S;
import org.matheclipse.core.interfaces.IExpr;
import org.matheclipse.gpl.numbertheory.BigIntegerPrimality;
import org.matheclipse.image.builtin.ImageFunctions;
import org.matheclipse.parser.client.ParserConfig;
import org.matheclipse.parser.client.SyntaxError;
import org.matheclipse.parser.client.math.MathException;
import goryachev.common.util.CList;
import goryachev.common.util.SB;
import goryachev.notebook.cell.CodePanel;
import goryachev.notebook.cell.NotebookPanel;
import goryachev.notebook.cell.Results;
import goryachev.swing.BackgroundThread;
import goryachev.swing.UI;


public class SymjaEngine {

  static {
    ToggleFeature.COMPILE = true;
    ToggleFeature.COMPILE_PRINT = true;
    ParserConfig.PARSER_USE_LOWERCASE_SYMBOLS = false;
    Config.SHORTEN_STRING_LENGTH = 80;
    Config.MAX_AST_SIZE = 20000;
    Config.MAX_MATRIX_DIMENSION_SIZE = 100;
    Config.MAX_BIT_LENGTH = 200000;
    Config.MAX_POLYNOMIAL_DEGREE = 100;
    Config.FILESYSTEM_ENABLED = true;
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

    S.ListPlot.setEvaluator(new org.matheclipse.image.bridge.fig.ListPlot());
    S.Histogram.setEvaluator(new org.matheclipse.image.bridge.fig.Histogram());
    S.Plot.setEvaluator(new org.matheclipse.image.bridge.fig.Plot());
  }

  private final NotebookPanel np;
  private ExprEvaluator evaluator;

  // protected ScriptableObject scope;
  // protected GlobalScope globalScope;
  private AtomicInteger sequence = new AtomicInteger(1);
  private volatile SymjaThread thread;
  private SB log = new SB();
  protected static final ThreadLocal<SymjaEngine> engineRef = new ThreadLocal();
  protected static final ThreadLocal<CodePanel> codePanelRef = new ThreadLocal();


  public SymjaEngine(NotebookPanel np) {
    this.np = np;

    boolean relaxedSyntax = false;
    EvalEngine engine = new EvalEngine(relaxedSyntax);
    EvalEngine.set(engine);
    engine.init();
    engine.setRecursionLimit(512);
    engine.setIterationLimit(500);
    engine.setOutListDisabled(false, (short) 10);

    evaluator = new ExprEvaluator(engine, false, (short) 100);
  }


  // protected synchronized Scriptable scope(Context cx) throws Exception {
  // if (scope == null) {
  // scope = new GlobalScope(cx);
  // }
  //
  // return scope;
  // }


  public void setSequence(int x) {
    sequence.set(x);
  }


  protected synchronized void print(String s) {
    if (log.isNotEmpty()) {
      log.nl();
    }

    // TODO check for full buffer and append message "Too many lines"

    log.append(s);
  }


  public synchronized void display(Object x) {
    Object v = Results.copyValue(x);
    CodePanel p = codePanelRef.get();

    if (v instanceof Object[]) {
      for (Object item : (Object[]) v) {
        if (item != null) {
          displayPrivate(p, item);
        }
      }
    } else {
      displayPrivate(p, v);
    }
  }


  protected void displayPrivate(final CodePanel p, final Object v) {
    // make sure to show text logged so far
    final String text;
    if (log.isNotEmpty()) {
      text = log.getAndClear();
    } else {
      text = null;
    }

    UI.inEDTW(new Runnable() {
      @Override
      public void run() {
        if (text != null) {
          p.addResult(text);
        }

        p.addResult(v);
      }
    });
  }


  public void execute(final CodePanel p) {
    p.setRunning(true);

    final String script = p.getText();

    thread = new SymjaThread() {
      @Override
      public void process() throws Throwable {
        try {
          engineRef.set(SymjaEngine.this);
          codePanelRef.set(p);

          IExpr rv = evaluator.eval(script);

          // "line " produces an error message like "line #5"
          // Object rv = cx.evaluateString(scope(cx), script, "line ", 1, null);
          display(rv);
        } catch (SyntaxError e) {
          // catch Symja parser errors here
          displayPrivate(p, new SymjaError(e.getMessage()));
        } catch (MathException me) {
          // catch Symja math errors here
          displayPrivate(p, new SymjaError(me.getMessage()));
        } catch (final Exception ex) {
          displayPrivate(p, new SymjaError(ex.getMessage()));
        } catch (final StackOverflowError soe) {
          displayPrivate(p, new SymjaError(soe.getMessage()));
        } catch (final OutOfMemoryError oome) {
          displayPrivate(p, new SymjaError(oome.getMessage()));
        } finally {
          codePanelRef.set(null);
          // Context.exit();

          executeOnFinishCallbacks();
        }
        // Context cx = Context.enter();
        //
        // // set interpreted mode so we can stick interruption check in Interpreter.interpretLoop()
        // cx.setOptimizationLevel(-1);
        //
        // try {
        // engineRef.set(JsEngine.this);
        // codePanelRef.set(p);
        //
        // // "line " produces an error message like "line #5"
        // Object rv = cx.evaluateString(scope(cx), script, "line ", 1, null);
        // display(rv);
        // } finally {
        // codePanelRef.set(null);
        // Context.exit();
        //
        // executeOnFinishCallbacks();
        // }
      }

      @Override
      public void success() {
        finished(p);
      }

      @Override
      public void onError(Throwable e) {
        displayPrivate(p, new SymjaError(SymjaUtil.decodeException(e)));
        finished(p);
      }
    };
    thread.start();
  }


  public void addOnFinishCallback(Runnable r) {
    SymjaThread t = thread;
    if (t != null) {
      t.addOnFinishCallback(r);
    }
  }


  protected void finished(CodePanel p) {
    thread = null;

    int count = sequence.getAndIncrement();
    p.setFinished(count);

    // UI.scrollRectToVisible(p);
  }


  public void execute(CList<CodePanel> ps) {
    // TODO
  }


  public void interrupt() {
    BackgroundThread t = thread;
    if (t != null) {
      t.interrupt();
    }
  }


  public boolean isRunning() {
    return (thread != null);
  }


  public static SymjaEngine get() {
    return engineRef.get();
  }
}

package fr.umlv.smalljs.astinterp;

import fr.umlv.smalljs.ast.Expr;
import fr.umlv.smalljs.ast.Expr.Block;
import fr.umlv.smalljs.ast.Expr.Call;
import fr.umlv.smalljs.ast.Expr.FieldAccess;
import fr.umlv.smalljs.ast.Expr.FieldAssignment;
import fr.umlv.smalljs.ast.Expr.Fun;
import fr.umlv.smalljs.ast.Expr.Identifier;
import fr.umlv.smalljs.ast.Expr.If;
import fr.umlv.smalljs.ast.Expr.Literal;
import fr.umlv.smalljs.ast.Expr.MethodCall;
import fr.umlv.smalljs.ast.Expr.ObjectLiteral;
import fr.umlv.smalljs.ast.Expr.Return;
import fr.umlv.smalljs.ast.Expr.VarAssignment;
import fr.umlv.smalljs.ast.Script;
import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;
import static java.util.stream.Collectors.joining;

public final class ASTInterpreter {
  private static JSObject asJSObject(Object value, int lineNumber) {
    if (!(value instanceof JSObject jsObject)) {
      throw new Failure("at line " + lineNumber + ", type error " + value + " is not a JSObject");
    }
    return jsObject;
  }

  private static Object execute(Expr.Block body, JSObject env) {
    // initialize declared variables to UNDEFINED
    visitVariable(body, env);
    // interpret the AST
    return visit(body, env);
  }

  private static void visitVariable(Expr expression, JSObject env) {
    switch (expression) {
      case Block(List<Expr> exprs, _) -> {
        for (var expr : exprs) {
          visitVariable(expr, env);
        }
      }
      case VarAssignment(String name, _, boolean declaration, _) -> {
        if (declaration) {
          env.register(name, UNDEFINED);
        }
      }
      case If(_, Block trueBlock, Block falseBlock, _) -> {
        visitVariable(trueBlock, env);
        visitVariable(falseBlock, env);
      }
      case Literal _, Call _, Identifier _, Fun _, Return _, ObjectLiteral _, FieldAccess _,
           FieldAssignment _, MethodCall _ -> {
        // do nothing
      }
    };
  }

  static Object visit(Expr expression, JSObject env) {
    return switch (expression) {
      case Block(List<Expr> exprs, int lineNumber) -> {
        for (var expr: exprs) {
          visit(expr, env);
        }
        yield UNDEFINED;
      }
      case Literal(Object value, int lineNumber) -> {
        yield value;
      }
      case Call(Expr qualifier, List<Expr> args, int lineNumber) -> {
        var vamie = visit(qualifier, env);
        if (!(vamie instanceof JSObject function)) {
          throw new Failure("invalid function expression: " + vamie + " at line " + lineNumber);
        }
        var arguments =  args.stream().map(arg -> visit(arg, env)).toArray();
        yield function.invoke(UNDEFINED, arguments);
      }
      case Identifier(String name, int lineNumber) -> {
        var res = env.lookupOrDefault(name, null);
        if (res == null) {
          throw new Failure("unknown identifier: " + name +  " at line " + lineNumber);
        }
        yield res;
      }
      case VarAssignment(String name, Expr expr, boolean declaration, int lineNumber) -> {
        var value = visit(expr, env);
        if (!declaration && env.lookupOrDefault(name, null) == null) {
          throw new Failure("unknown variable: " + name + " at line " + lineNumber);
        }
        env.register(name, value);
        yield value;
      }
      case Fun(String name, List<String> parameters, boolean toplevel, Block body, int lineNumber) -> {
        JSObject.Invoker invoker = new JSObject.Invoker() {
          @Override
          public Object invoke(Object receiver, Object... args) {
            // check the arguments length
            if (args.length != parameters.size()) {
              throw new Failure("invalid function arguments: " + Arrays.toString(parameters.toArray()) + " at line " + lineNumber);
            }
            // create a new environment
            var newEnv = JSObject.newEnv(env);
            // add this and all the parameters
            newEnv.register("this",receiver);
            for (int i = 0; i < parameters.size(); i++) {
              newEnv.register(parameters.get(i), args[i]);
            }
            // execute the body
            try {
              execute(body, newEnv);
            } catch(ReturnError e) {
              return e.getValue();
            }
            return UNDEFINED;
          }
        };
        var fun = JSObject.newFunction(name, invoker);
        if(toplevel) {
          env.register(name, fun);
        }
        yield fun;
      }
      case Return(Expr expr, int lineNumber) -> {
        var res = visit(expr, env);
        throw new ReturnError(res);
      }
      case If(Expr condition, Block trueBlock, Block falseBlock, int lineNumber) -> {
        var resCond = visit(condition, env);
        if (resCond.equals(UNDEFINED) || resCond.equals(0) || resCond.equals("")) {
          visit(falseBlock, env);
        } else {
          visit(trueBlock, env);
        }
        yield UNDEFINED;
      }
      case ObjectLiteral(Map<String, Expr> initMap, int lineNumber) -> {
				throw new UnsupportedOperationException("TODO ObjectLiteral");
      }
      case FieldAccess(Expr receiver, String name, int lineNumber) -> {
        throw new UnsupportedOperationException("TODO FieldAccess");
      }
      case FieldAssignment(Expr receiver, String name, Expr expr, int lineNumber) -> {
        throw new UnsupportedOperationException("TODO FieldAssignment");
      }
      case MethodCall(Expr receiver, String name, List<Expr> args, int lineNumber) -> {
        throw new UnsupportedOperationException("TODO MethodCall");
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static JSObject createGlobalEnv(PrintStream outStream) {
    var globalEnv = JSObject.newEnv(null);
    globalEnv.register("globalThis", globalEnv);
    globalEnv.register("print", JSObject.newFunction("print", (_, args) -> {
      System.err.println("print called with " + Arrays.toString(args));
      outStream.println(Arrays.stream(args).map(Object::toString).collect(Collectors.joining(" ")));
      return UNDEFINED;
    }));
    globalEnv.register("+", JSObject.newFunction("+", (_, args) -> (Integer) args[0] + (Integer) args[1]));
    globalEnv.register("-", JSObject.newFunction("-", (_, args) -> (Integer) args[0] - (Integer) args[1]));
    globalEnv.register("/", JSObject.newFunction("/", (_, args) -> (Integer) args[0] / (Integer) args[1]));
    globalEnv.register("*", JSObject.newFunction("*", (_, args) -> (Integer) args[0] * (Integer) args[1]));
    globalEnv.register("%", JSObject.newFunction("%", (_, args) -> (Integer) args[0] % (Integer) args[1]));
    globalEnv.register("==", JSObject.newFunction("==", (_, args) -> args[0].equals(args[1]) ? 1 : 0));
    globalEnv.register("!=", JSObject.newFunction("!=", (_, args) -> !args[0].equals(args[1]) ? 1 : 0));
    globalEnv.register("<", JSObject.newFunction("<", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) < 0) ? 1 : 0));
    globalEnv.register("<=", JSObject.newFunction("<=", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) <= 0) ? 1 : 0));
    globalEnv.register(">", JSObject.newFunction(">", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) > 0) ? 1 : 0));
    globalEnv.register(">=", JSObject.newFunction(">=", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) >= 0) ? 1 : 0));
    return globalEnv;
  }

  public static void interpret(Script script, PrintStream outStream) {
    var globalEnv = createGlobalEnv(outStream);
    var body = script.body();
    execute(body, globalEnv);
  }
}


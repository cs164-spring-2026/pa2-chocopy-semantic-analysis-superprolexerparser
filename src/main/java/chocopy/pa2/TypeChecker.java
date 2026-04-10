package chocopy.pa2;

import chocopy.common.analysis.AbstractNodeAnalyzer;
import chocopy.common.analysis.SymbolTable;
import chocopy.common.analysis.types.Type;
import chocopy.common.analysis.types.ValueType;
import chocopy.common.astnodes.BinaryExpr;
import chocopy.common.astnodes.Declaration;
import chocopy.common.astnodes.Errors;
import chocopy.common.astnodes.ExprStmt;
import chocopy.common.astnodes.Identifier;
import chocopy.common.astnodes.IntegerLiteral;
import chocopy.common.astnodes.Node;
import chocopy.common.astnodes.Program;
import chocopy.common.astnodes.Stmt;
import chocopy.common.analysis.types.*;
import chocopy.common.astnodes.*;
import java.util.*;
import static chocopy.common.analysis.types.Type.*;
import java.util.HashMap;

import static chocopy.common.analysis.types.Type.INT_TYPE;
import static chocopy.common.analysis.types.Type.OBJECT_TYPE;

/** Analyzer that performs ChocoPy type checks on all nodes.  Applied after
 *  collecting declarations. */
public class TypeChecker extends AbstractNodeAnalyzer<Type> {

    /** The current symbol table (changes depending on the function
     *  being analyzed). */
    private SymbolTable<Type> sym;
    private final SymbolTable<Type> symGlobal;

    private final HashMap<String, SymbolTable<Type>> classScopes;
    private final HashMap<String, String> parentMap;
    private final HashMap<FuncDef, SymbolTable<Type>> funcScopes;



    /** Collector for errors. */
    private Errors errors;

    private Type expectedRetType = null;
    private boolean inFunction = false;



    /** Creates a type checker using GLOBALSYMBOLS for the initial global
     *  symbol table and ERRORS0 to receive semantic errors. */
    public TypeChecker(SymbolTable<Type> globalSymbols, Errors errors0, HashMap<String, String> inheritanceTree,HashMap<String, SymbolTable<Type>> classScopes0, HashMap<FuncDef,SymbolTable<Type>> funcScopes0) {
        sym = globalSymbols;
        symGlobal = globalSymbols;
        errors = errors0;
        classScopes = classScopes0;
        parentMap = inheritanceTree;
        funcScopes = funcScopes0;
    }

    /** Inserts an error message in NODE if there isn't one already.
     *  The message is constructed with MESSAGE and ARGS as for
     *  String.format. */
    private void err(Node node, String message, Object... args) {
        errors.semError(node, message, args);
    }

    @Override
    public Type analyze(Program program) {
        for (Declaration decl : program.declarations) {
            decl.dispatch(this);
        }
        for (Stmt stmt : program.statements) {
            stmt.dispatch(this);
        }
        return null;
    }
    @Override
    public Type analyze(GlobalDecl decl) {
        String name = decl.variable.name;
        boolean isClass = classScopes != null && classScopes.containsKey(name) || Arrays.asList("int", "str", "bool", "object").contains(name);
        if (!symGlobal.declares(name) || symGlobal.get(name) instanceof FuncType || isClass) {
            err(decl.variable, "Not a global variable: %s", name);
            sym.put(name, OBJECT_TYPE);
        } else {
            sym.put(name, symGlobal.get(name));
        }
        return null;
    }

    @Override
    public Type analyze(NonLocalDecl decl) {
        String name = decl.variable.name;
        SymbolTable<Type> current = sym.getParent();
        Type found = null;

        while (current != null && current != symGlobal) {
            FuncDef parentFunc = null;
            for (Map.Entry<FuncDef, SymbolTable<Type>> entry : funcScopes.entrySet()) {
                if (entry.getValue() == current) {
                    parentFunc = entry.getKey();
                    break;
                }
            }

            if (parentFunc != null) {
                boolean isLocal = false;

                // Check if the variable is genuinely bound as a parameter...
                for (TypedVar param : parentFunc.params) {
                    if (param.identifier.name.equals(name)) { isLocal = true; break; }
                }

                if (!isLocal) {
                    for (Declaration d : parentFunc.declarations) {
                        if (d instanceof VarDef || d instanceof FuncDef || d instanceof ClassDef) {
                            if (d.getIdentifier().name.equals(name)) { isLocal = true; break; }
                        }
                    }
                }

                if (isLocal) {
                    found = current.get(name);
                    break;
                }
            }
            current = current.getParent();
        }

        boolean isClass = classScopes != null && classScopes.containsKey(name) || Arrays.asList("int", "str", "bool", "object").contains(name);
        if (found == null || found instanceof FuncType || isClass) {
            err(decl.variable, "Not a nonlocal variable: %s", name);
            sym.put(name, OBJECT_TYPE);
        } else {
            sym.put(name, found);
        }
        return null;
    }
    @Override
    public Type analyze(VarDef varDef) {
        String identifierName = varDef.var.identifier.name;
        Type declaredType = sym.get(identifierName);
        Type assignedValueType = varDef.value.dispatch(this);

        if (!isTypeCompatible(declaredType, assignedValueType)) {
            err(varDef, "Expected type `%s`; got type `%s`", declaredType, assignedValueType);
        }
        return null;
    }

    @Override
    public Type analyze(FuncDef func) {
        String functionName = func.name.name;
        Type functionType = sym.get(functionName);

        SymbolTable<Type> outerEnv = sym;
        if (funcScopes != null && funcScopes.containsKey(func)) {
            sym = funcScopes.get(func);
        } else {
            sym = new SymbolTable<>(sym);
        }

        Type previousRetType = expectedRetType;
        if (functionType instanceof FuncType) {
            expectedRetType = ((FuncType) functionType).returnType;
        } else {
            expectedRetType = null;
        }

        for (Declaration decl : func.declarations) {
            if (decl instanceof GlobalDecl || decl instanceof NonLocalDecl) {
                decl.dispatch(this);
            }
        }

        for (Declaration decl : func.declarations) {
            if (!(decl instanceof GlobalDecl || decl instanceof NonLocalDecl)) {
                decl.dispatch(this);
            }
        }
        boolean wasInFunction = inFunction;
        inFunction = true;
        for (Stmt stmt : func.statements) {
            stmt.dispatch(this);
        }
        inFunction = wasInFunction;

        String retClassName = "<None>";
        if (func.returnType instanceof ClassType) {
            retClassName = ((ClassType) func.returnType).className;
        }

        if (retClassName.equals("int") || retClassName.equals("bool") || retClassName.equals("str")) {
            if (!checkReturn(func.statements)) {
                errors.semError(func.getIdentifier(), "All paths in this function/method must have a return statement: %s", functionName);
            }
        }

        sym = outerEnv;
        expectedRetType = previousRetType;
        return null;
    }

    @Override
    public Type analyze(ClassDef cls) {
        String className = cls.name.name;
        SymbolTable<Type> priorEnv = sym;


        if (classScopes != null && classScopes.containsKey(className)) {
            sym = classScopes.get(className);
        }

        for (Declaration decl : cls.declarations) {
            decl.dispatch(this);
        }

        sym = priorEnv;
        return null;
    }

    @Override
    public Type analyze(BinaryExpr e) {
        Type t1 = e.left.dispatch(this);
        Type t2 = e.right.dispatch(this);
        if (t1 == null) t1 = OBJECT_TYPE;
        if (t2 == null) t2 = OBJECT_TYPE;

        switch (e.operator) {
            case "-":
            case "*":
            case "//":
            case "%":
                if (!INT_TYPE.equals(t1) || !INT_TYPE.equals(t2)) {
                    err(e, "Cannot apply operator `%s` on types `%s` and `%s`", e.operator, t1, t2);
                }
                return e.setInferredType(INT_TYPE);
            case "+":
                if (INT_TYPE.equals(t1) && INT_TYPE.equals(t2)) {
                    return e.setInferredType(INT_TYPE);
                } else if (STR_TYPE.equals(t1) && STR_TYPE.equals(t2)) {
                    return e.setInferredType(STR_TYPE);
                } else if (t1.isListType() && t2.isListType()) {
                    String t1Name = t1.elementType().className();
                    String t2Name = t2.elementType().className();
                    Type lub = new ClassValueType(findCommonBaseClass(t1Name, t2Name));
                    return e.setInferredType(new ListValueType(lub));
                } else {
                    err(e, "Cannot apply operator `%s` on types `%s` and `%s`", e.operator, t1, t2);
                    return e.setInferredType(INT_TYPE.equals(t1) || INT_TYPE.equals(t2) ? INT_TYPE : OBJECT_TYPE);
                }
            case "<":
            case "<=":
            case ">":
            case ">=":
                if (!INT_TYPE.equals(t1) || !INT_TYPE.equals(t2)) {
                    err(e, "Cannot apply operator `%s` on types `%s` and `%s`", e.operator, t1, t2);
                }
                return e.setInferredType(BOOL_TYPE);
            case "==":
            case "!=":
                if (!t1.equals(t2) || (!INT_TYPE.equals(t1) && !STR_TYPE.equals(t1) && !BOOL_TYPE.equals(t1))) {
                    err(e, "Cannot apply operator `%s` on types `%s` and `%s`", e.operator, t1, t2);
                }
                return e.setInferredType(BOOL_TYPE);
            case "and":
            case "or":
                if (!BOOL_TYPE.equals(t1) || !BOOL_TYPE.equals(t2)) {
                    err(e, "Cannot apply operator `%s` on types `%s` and `%s`", e.operator, t1, t2);
                }
                return e.setInferredType(BOOL_TYPE);
            case "is":
                if (INT_TYPE.equals(t1) || STR_TYPE.equals(t1) || BOOL_TYPE.equals(t1) ||
                        INT_TYPE.equals(t2) || STR_TYPE.equals(t2) || BOOL_TYPE.equals(t2)) {
                    err(e, "Cannot apply operator `%s` on types `%s` and `%s`", e.operator, t1, t2);
                }
                return e.setInferredType(BOOL_TYPE);
            default:
                return e.setInferredType(OBJECT_TYPE);
        }
    }

    @Override
    public Type analyze(UnaryExpr expr) {
        Type innerType = expr.operand.dispatch(this);

        if (expr.operator.equals("-")) {
            if (!INT_TYPE.equals(innerType)) {
                err(expr, "Cannot apply operator `-` on type `%s`", innerType);
            }
            return expr.setInferredType(INT_TYPE);
        } else if (expr.operator.equals("not")) {
            if (!BOOL_TYPE.equals(innerType)) {
                err(expr, "Cannot apply operator `not` on type `%s`", innerType);
            }
            return expr.setInferredType(BOOL_TYPE);
        }
        return expr.setInferredType(OBJECT_TYPE);
    }

    @Override
    public Type analyze(CallExpr call) {
        if (call.function instanceof Identifier) {
            String targetName = ((Identifier) call.function).name;
            boolean isClass = (classScopes != null && classScopes.containsKey(targetName)) ||
                    Arrays.asList("int", "str", "bool", "object").contains(targetName);
            if (!isClass) {
                call.function.dispatch(this);
            }
        } else {
            call.function.dispatch(this);
        }

        String targetName = call.function.name;
        Type targetType = sym.get(targetName);

        for (Expr arg : call.args) {
            arg.dispatch(this);
        }

        if (targetType instanceof FuncType) {
            FuncType fType = (FuncType) targetType;
            if (call.args.size() != fType.parameters.size() && !targetName.equals("print")) {
                err(call, "Expected %d arguments; got %d", fType.parameters.size(), call.args.size());
            } else {
                for (int i = 0; i < Math.min(call.args.size(), fType.parameters.size()); i++) {
                    Type argType = call.args.get(i).getInferredType();
                    if (!isTypeCompatible(fType.parameters.get(i), argType)) {
                        err(call, "Expected type `%s`; got type `%s` in parameter %d",
                                fType.parameters.get(i), argType, i);
                    }
                }
            }
            return call.setInferredType(fType.returnType);
        } else if (classScopes != null && classScopes.containsKey(targetName)) {
            return call.setInferredType(new ClassValueType(targetName));
        }

        err(call, "Not a function or class: %s", targetName);
        return call.setInferredType(OBJECT_TYPE);
    }

    @Override
    public Type analyze(IndexExpr idxExpr) {
        Type sequenceType = idxExpr.list.dispatch(this);
        Type offsetType = idxExpr.index.dispatch(this);

        if (!STR_TYPE.equals(sequenceType) && !sequenceType.isListType()) {
            err(idxExpr, "Cannot index into type `%s`", sequenceType);
            return idxExpr.setInferredType(OBJECT_TYPE);
        }

        if (!INT_TYPE.equals(offsetType)) {
            err(idxExpr, "Index is of non-integer type `%s`", offsetType);
        }

        return idxExpr.setInferredType(sequenceType.isListType() ? sequenceType.elementType() : STR_TYPE);
    }
    @Override
    public Type analyze(ListExpr listNode) {
        if (listNode.elements.isEmpty()) {
            return listNode.setInferredType(EMPTY_TYPE);
        }
        Type commonBase = null;
        for (Expr item : listNode.elements) {
            Type currentItemType = item.dispatch(this);
            if (commonBase == null) {
                commonBase = currentItemType;
            } else {
                commonBase = new ClassValueType(findCommonBaseClass(commonBase.className(), currentItemType.className()));
            }
        }
        return listNode.setInferredType(new ListValueType(commonBase));
    }
    @Override
    public Type analyze(MemberExpr expr) {
        Type objType = expr.object.dispatch(this);
        String attrName = expr.member.name;

        if (objType == null) return expr.setInferredType(OBJECT_TYPE);

        if (objType instanceof ClassValueType) {
            String currentClass = objType.className();

            while (currentClass != null && classScopes.containsKey(currentClass)) {
                SymbolTable<Type> scope = classScopes.get(currentClass);
                if (scope.declares(attrName)) {
                    Type attrType = scope.get(attrName);
                    return expr.setInferredType(attrType);
                }
                currentClass = parentMap.get(currentClass);
            }
            err(expr, "There is no attribute named `%s` in class `%s`", attrName, objType.className());
        } else {
            err(expr, "Cannot access attribute on non-class type `%s`", objType.className());
        }
        return expr.setInferredType(OBJECT_TYPE);
    }
    @Override
    public Type analyze(ForStmt s) {
        Type iterType = s.iterable.dispatch(this);
        Type idType = s.identifier.dispatch(this);

        if (iterType != null && !iterType.isListType() && !STR_TYPE.equals(iterType)) {
            err(s.iterable, "Cannot iterate over value of type `%s`", iterType);
        } else if (iterType != null && idType != null) {
            Type elementType = iterType.isListType() ? iterType.elementType() : STR_TYPE;
            if (!isTypeCompatible(idType, elementType)) {
                err(s.identifier, "Expected type `%s`; got type `%s`", idType, elementType);
            }
        }

        for (Stmt stmt : s.body) {
            stmt.dispatch(this);
        }
        return null;
    }

    @Override
    public Type analyze(IfExpr expr) {
        Type condType = expr.condition.dispatch(this);
        if (!BOOL_TYPE.equals(condType)) {
            err(expr.condition, "Condition expression cannot be of type `%s`", condType);
        }

        Type thenType = expr.thenExpr.dispatch(this);
        Type elseType = expr.elseExpr.dispatch(this);

        if (thenType == null) thenType = OBJECT_TYPE;
        if (elseType == null) elseType = OBJECT_TYPE;

        String lub = findCommonBaseClass(thenType.className(), elseType.className());
        return expr.setInferredType(new ClassValueType(lub));
    }
    @Override
    public Type analyze(MethodCallExpr expr) {
        Type objType = expr.method.object.dispatch(this);
        String methodName = expr.method.member.name;

        for (Expr arg : expr.args) arg.dispatch(this);

        if (objType == null) return expr.setInferredType(OBJECT_TYPE);

        Type methodType = null;
        if (objType instanceof ClassValueType) {
            String currentClass = objType.className();
            while (currentClass != null && classScopes.containsKey(currentClass)) {
                SymbolTable<Type> scope = classScopes.get(currentClass);
                if (scope.declares(methodName)) {
                    methodType = scope.get(methodName);
                    break;
                }
                currentClass = parentMap.get(currentClass);
            }
        }
        if (methodType == null) methodType = OBJECT_TYPE;

        // Preserve typed AST coverage: method MemberExpr should carry inferred FuncType.
        expr.method.setInferredType(methodType);

        if (methodType instanceof FuncType) {
            FuncType fType = (FuncType) methodType;
            int expectedArgs = fType.parameters.size() - 1;

            if (expr.args.size() != expectedArgs) {
                err(expr, "Expected %d arguments; got %d", expectedArgs, expr.args.size());
            } else {
                for (int i = 0; i < Math.min(expr.args.size(), expectedArgs); i++) {
                    Type argType = expr.args.get(i).getInferredType();
                    Type paramType = fType.parameters.get(i + 1);
                    if (!isTypeCompatible(paramType, argType)) {
                        err(expr, "Expected type `%s`; got type `%s` in parameter %d", paramType, argType, i+1);
                    }
                }
            }
            return expr.setInferredType(fType.returnType);
        }

        if (objType instanceof ClassValueType) {
            err(expr, "There is no method named `%s` in class `%s`", methodName, objType.className());
        } else {
            err(expr, "Cannot call method on non-class type `%s`", objType.className());
        }

        return expr.setInferredType(OBJECT_TYPE);
    }

    @Override
    public Type analyze(AssignStmt assignNode) {
        Type rightHandType = assignNode.value.dispatch(this);

        if (assignNode.targets.size() > 1 && rightHandType.isListType() && NONE_TYPE.equals(rightHandType.elementType())) {
            err(assignNode, "Right-hand side of multiple assignment may not be [<None>]");
        }

        for (Expr target : assignNode.targets) {
            if (inFunction && target instanceof Identifier) {
                String name = ((Identifier) target).name;
                if (!sym.declares(name)) {
                    err(target, "Cannot assign to variable that is not explicitly declared in this scope: %s", name);
                }
            }
            Type leftHandType = target.dispatch(this);
            if (target instanceof IndexExpr) {
                Type listType = ((IndexExpr) target).list.getInferredType();
                if (STR_TYPE.equals(listType)) {
                    err(target, "`str` is not a list type");
                }
            }
            if (leftHandType != null && !isTypeCompatible(leftHandType, rightHandType)) {
                err(assignNode, "Expected type `%s`; got type `%s`", leftHandType, rightHandType);
            }
        }
        return null;
    }

    @Override
    public Type analyze(ReturnStmt retNode) {
        if (expectedRetType == null) {
            err(retNode, "Return statement cannot appear at the top level");
            return null;
        }

        Type actualRetType = (retNode.value != null) ? retNode.value.dispatch(this) : NONE_TYPE;

        if (!isTypeCompatible(expectedRetType, actualRetType)) {
            if (retNode.value == null) {
                err(retNode, "Expected type `%s`; got `None`", expectedRetType);
            } else {
                err(retNode, "Expected type `%s`; got type `%s`", expectedRetType, actualRetType);
            }
        }
        return null;
    }


    @Override
    public Type analyze(Identifier id) {
        String varName = id.name;
        Type varType = sym.get(varName);

        if (varType!= null) {
            return id.setInferredType(varType);
        } else if (symGlobal.declares(varName)) {
            return id.setInferredType(symGlobal.get(varName));
        }

        err(id, "Not a variable: %s", varName);
        return id.setInferredType(ValueType.OBJECT_TYPE);
    }

    @Override public Type analyze(ExprStmt s) { s.expr.dispatch(this); return null; }
    @Override public Type analyze(IntegerLiteral i) { return i.setInferredType(INT_TYPE); }
    @Override public Type analyze(StringLiteral s) { return s.setInferredType(STR_TYPE); }
    @Override public Type analyze(BooleanLiteral b) { return b.setInferredType(BOOL_TYPE); }
    @Override public Type analyze(NoneLiteral n) { return n.setInferredType(NONE_TYPE); }

    @Override
    public Type analyze(IfStmt ifNode) {
        Type condType = ifNode.condition.dispatch(this);
        if (!BOOL_TYPE.equals(condType)) {
            err(ifNode, "Condition expression cannot be of type `%s`", condType);
        }
        for (Stmt s : ifNode.thenBody) s.dispatch(this);
        for (Stmt s : ifNode.elseBody) s.dispatch(this);
        return null;
    }

    @Override
    public Type analyze(WhileStmt whileNode) {
        Type condType = whileNode.condition.dispatch(this);
        if (!BOOL_TYPE.equals(condType)) {
            err(whileNode, "Condition expression cannot be of type `%s`", condType);
        }
        for (Stmt s : whileNode.body) s.dispatch(this);
        return null;
    }




    private boolean isTypeCompatible(Type targetDef, Type assignedVal) {
        if (targetDef == null || assignedVal == null) return false;

        if (!targetDef.isSpecialType() && NONE_TYPE.equals(assignedVal)) return true;
        if (targetDef instanceof ListValueType && EMPTY_TYPE.equals(assignedVal)) return true;

        if (targetDef instanceof ListValueType && assignedVal instanceof ListValueType) {
            Type targetElem = targetDef.elementType();
            Type valElem = assignedVal.elementType();
            if (NONE_TYPE.equals(valElem)) return isTypeCompatible(targetElem, NONE_TYPE);
            return targetElem.equals(valElem);
        }
        return isDescendantOf(targetDef.className(), assignedVal.className());
    }

    private boolean isDescendantOf(String parentClass, String childClass) {
        if ("object".equals(parentClass) || parentClass.equals(childClass)) return true;
        if (parentMap == null) return false;

        String currentAncestor = parentMap.get(childClass);
        while (currentAncestor != null) {
            if (currentAncestor.equals(parentClass)) return true;
            currentAncestor = parentMap.get(currentAncestor);
        }
        return false;
    }

    private String findCommonBaseClass(String classA, String classB) {
        if (classA == null) return classB;
        if (classB == null) return classA;

        List<String> pathA = getInheritanceChain(classA);
        List<String> pathB = getInheritanceChain(classB);

        String commonNode = "object";
        for (int i = 0; i < Math.min(pathA.size(), pathB.size()); i++) {
            if (pathA.get(i).equals(pathB.get(i))) {
                commonNode = pathA.get(i);
            } else {
                break;
            }
        }
        return commonNode;
    }

    private List<String> getInheritanceChain(String leafClass) {
        List<String> chain = new ArrayList<>();
        while (leafClass != null && !leafClass.equals("object")) {
            chain.add(leafClass);
            leafClass = parentMap != null ? parentMap.get(leafClass) : null;
        }
        chain.add("object");
        Collections.reverse(chain);
        return chain;
    }
    private boolean checkReturn(List<Stmt> stmts) {
        if (stmts == null || stmts.isEmpty()) return false;
        Stmt last = stmts.get(stmts.size() - 1);
        if (last instanceof ReturnStmt) return true;
        if (last instanceof IfStmt) {
            IfStmt ifStmt = (IfStmt) last;
            return checkReturn(ifStmt.thenBody) && checkReturn(ifStmt.elseBody);
        }
        return false;
    }
}

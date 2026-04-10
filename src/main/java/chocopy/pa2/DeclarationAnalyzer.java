package chocopy.pa2;

import chocopy.common.analysis.AbstractNodeAnalyzer;
import chocopy.common.analysis.SymbolTable;
import chocopy.common.analysis.types.Type;
import chocopy.common.analysis.types.ValueType;
import chocopy.common.astnodes.Declaration;
import chocopy.common.astnodes.Errors;
import chocopy.common.astnodes.Identifier;
import chocopy.common.astnodes.Program;
import chocopy.common.astnodes.VarDef;

import chocopy.common.analysis.types.*;
import chocopy.common.astnodes.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static chocopy.common.analysis.types.Type.*;

/**
 * Analyzes declarations to create a top-level symbol table.
 */
public class DeclarationAnalyzer extends AbstractNodeAnalyzer<Type> {

    /** Current symbol table.  Changes with new declarative region. */
    private SymbolTable<Type> sym = new SymbolTable<>();
    /** Global symbol table. */
    private final SymbolTable<Type> globals = sym;
    /** Receiver for semantic error messages. */
    private final Errors errors;

    private final HashMap<String, SymbolTable<Type>> classScopes = new HashMap<>();
    private final HashMap<String, String> parentMap = new HashMap<>();

    private final HashMap<FuncDef, SymbolTable<Type>> funcScopes = new HashMap<>();
    private String currentClassName = null;
    private final HashSet<String> declaredClasses = new HashSet<>();

    /** A new declaration analyzer sending errors to ERRORS0. */
    public DeclarationAnalyzer(Errors errors0) {
        errors = errors0;
    }


    public SymbolTable<Type> getGlobals() {
        return globals;
    }

    public HashMap<String, SymbolTable<Type>> getClassScopes() {
        return classScopes;
    }

    public HashMap<String, String> getParentMap() {
        return parentMap;
    }
    public HashMap<FuncDef, SymbolTable<Type>> getFuncScopes() {
        return funcScopes;
    }
    @Override
    public Type analyze(GlobalDecl decl) {
        return OBJECT_TYPE;
    }

    @Override
    public Type analyze(NonLocalDecl decl) {
        return OBJECT_TYPE;
    }

    private void validateTypeAnnotation(TypeAnnotation annotation) {
        if (annotation instanceof ClassType) {
            String className = ((ClassType) annotation).className;
            if (!isClass(className) && !className.equals("<None>")) {
                errors.semError(annotation, "Invalid type annotation; there is no class named: %s", className);
            }
        } else if (annotation instanceof ListType) {
            validateTypeAnnotation(((ListType) annotation).elementType);
        }
    }



    private boolean shadowsLocal(String name, SymbolTable<Type> currentEnv) {
        if (funcScopes != null) {
            for (java.util.Map.Entry<FuncDef, SymbolTable<Type>> entry : funcScopes.entrySet()) {
                if (entry.getValue() == currentEnv) {
                    for (TypedVar param : entry.getKey().params) {
                        if (param.identifier.name.equals(name)) return false;
                    }
                    break;
                }
            }
        }

        SymbolTable<Type> env = currentEnv.getParent();
        while (env != null && env != globals) {
            boolean isClassScope = classScopes != null && classScopes.containsValue(env);
            if (!isClassScope && env.declares(name)) {
                if (funcScopes != null) {
                    for (java.util.Map.Entry<FuncDef, SymbolTable<Type>> entry : funcScopes.entrySet()) {
                        if (entry.getValue() == env) {
                            FuncDef pFunc = entry.getKey();
                            for (TypedVar param : pFunc.params) {
                                if (param.identifier.name.equals(name)) return true;
                            }
                            for (Declaration d : pFunc.declarations) {
                                if ((d instanceof VarDef || d instanceof FuncDef) &&
                                        d.getIdentifier().name.equals(name)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            env = env.getParent();
        }
        return false;
    }

    private boolean isClass(String name) {
        return classScopes.containsKey(name) || declaredClasses.contains(name) ||
                name.equals("int") || name.equals("str") || name.equals("bool") || name.equals("object");
    }
    @Override
    public Type analyze(Program program) {

        sym.put("int", INT_TYPE);
        sym.put("str", STR_TYPE);
        sym.put("bool", BOOL_TYPE);
        sym.put("object", OBJECT_TYPE);

        List<ValueType> objParam = new ArrayList<>();
        objParam.add(OBJECT_TYPE);

        sym.put("print", new FuncType(objParam, NONE_TYPE));
        sym.put("input", new FuncType(new ArrayList<>(), STR_TYPE));
        sym.put("len", new FuncType(objParam, INT_TYPE));


        parentMap.put("object", null);

        SymbolTable<Type> objScope = new SymbolTable<>(sym);
        List<ValueType> initParams = new ArrayList<>();
        initParams.add(OBJECT_TYPE);
        objScope.put("__init__", new FuncType(initParams, NONE_TYPE));
        classScopes.put("object", objScope);
        for (Declaration decl : program.declarations) {
            if (decl instanceof ClassDef) {
                declaredClasses.add(decl.getIdentifier().name);
            }
        }

        for (Declaration decl : program.declarations) {
            Identifier id = decl.getIdentifier();
            String name = id.name;

            Type type = decl.dispatch(this);

            if (type == null) {
                continue;
            }

            if (sym.declares(name)) {
                errors.semError(id,
                                "Duplicate declaration of identifier in same "
                                + "scope: %s",
                                name);
            } else {
                sym.put(name, type);
            }
        }

        return null;
    }

    @Override
    public Type analyze(VarDef varDef) {
        validateTypeAnnotation(varDef.var.type);
        return ValueType.annotationToValueType(varDef.var.type);
    }
    @Override
    public Type analyze(FuncDef funcDef) {
        Identifier id = funcDef.getIdentifier();
        String name = id.name;

        List<ValueType> paramTypes = new ArrayList<>();
        for (TypedVar param : funcDef.params) {
            paramTypes.add((ValueType)ValueType.annotationToValueType(param.type));
        }
        validateTypeAnnotation(funcDef.returnType);
        Type returnType = (ValueType) ValueType.annotationToValueType(funcDef.returnType);

        if (currentClassName != null) {
            if (funcDef.params.isEmpty() || !funcDef.params.get(0).identifier.name.equals("self") ||
                    !(paramTypes.get(0) instanceof ClassValueType) || !((ClassValueType)paramTypes.get(0)).className().equals(currentClassName)) {
                errors.semError(funcDef.getIdentifier(), "First parameter of the following method must be of the enclosing class: %s", name);
            }
        }
        String savedClassName = currentClassName;
        currentClassName = null;
        SymbolTable<Type> funcEnv = new SymbolTable<>(sym);
        sym = funcEnv;
        funcScopes.put(funcDef, funcEnv);

        for (int i = 0; i < funcDef.params.size(); i++) {
            TypedVar param = funcDef.params.get(i);
            validateTypeAnnotation(param.type);
            String paramName = param.identifier.name;
            Type paramType = paramTypes.get(i);

            if (sym.declares(paramName)) {
                errors.semError(param.identifier, "Duplicate declaration of identifier in same scope: %s", paramName);
            } else if (shadowsLocal(paramName, sym)) {
                errors.semError(param.identifier, "Cannot shadow local variable: %s", paramName);
            } else if (isClass(paramName)) {
                errors.semError(param.identifier, "Cannot shadow class name: %s", paramName);
            } else {
                sym.put(paramName, paramType);
            }
        }

        for (Declaration decl : funcDef.declarations) {
            Identifier declId = decl.getIdentifier();
            String declName = declId.name;
            Type declType = decl.dispatch(this);

            if (declType == null) continue;

            if (sym.declares(declName)) {
                errors.semError(declId, "Duplicate declaration of identifier in same scope: %s", declName);
            } else if (!(decl instanceof GlobalDecl || decl instanceof NonLocalDecl)) {
                if (shadowsLocal(declName, sym)) {
                    errors.semError(declId, "Cannot shadow local variable: %s", declName);
                } else if (isClass(declName)) {
                    errors.semError(declId, "Cannot shadow class name: %s", declName);
                }
            }
            sym.put(declName, declType);
        }

        sym = sym.getParent();
        currentClassName = savedClassName;
        return new FuncType(paramTypes, (ValueType) returnType);
    }

    @Override
    public Type analyze(ClassDef classDef) {
        Identifier id = classDef.getIdentifier();
        String className = id.name;

        currentClassName = className;

        Identifier superClass = classDef.superClass;
        String superClassName = superClass.name;

        if (!superClassName.equals("object")) {
            Type superType = globals.get(superClassName);
            if (superType == null) {
                errors.semError(classDef.superClass, "Super-class not defined: %s", superClassName);
                superClassName = "object";
            } else if (!isClass(superClassName)) {
                errors.semError(classDef.superClass, "Super-class must be a class: %s", superClassName);
                superClassName = "object";
            } else if (superClassName.equals("int") || superClassName.equals("str") || superClassName.equals("bool")) {
                errors.semError(classDef.superClass, "Cannot extend special class: %s", superClassName);
                superClassName = "object";
            }
        }
        parentMap.put(className, superClassName);

        SymbolTable<Type> classEnv = new SymbolTable<>(sym);
        classScopes.put(className, classEnv);
        sym = classEnv;

        for (Declaration decl : classDef.declarations) {
            Identifier declId = decl.getIdentifier();
            String declName = declId.name;

            Type declType = decl.dispatch(this);
            if (declType == null) continue;

            if (sym.declares(declName)) {
                errors.semError(declId, "Duplicate declaration of identifier in same scope: %s", declName);
            } else {
                String currParent = superClassName;
                while (currParent != null) {
                    SymbolTable<Type> parentSym = classScopes.get(currParent);

                    if (parentSym != null && parentSym.declares(declName)) {
                        Type inheritedType = parentSym.get(declName);

                        if (!(declType instanceof FuncType) || !(inheritedType instanceof FuncType)) {
                            errors.semError(declId, "Cannot re-define attribute: %s", declName);
                        } else {
                            FuncType fDecl = (FuncType) declType;
                            FuncType fInherit = (FuncType) inheritedType;
                            boolean paramsMatch = fDecl.parameters.size() == fInherit.parameters.size();

                            if (paramsMatch) {
                                for (int i = 1; i < fDecl.parameters.size(); i++) {
                                    if (!fDecl.parameters.get(i).equals(fInherit.parameters.get(i))) {
                                        paramsMatch = false; break;
                                    }
                                }
                            }
                            if (!paramsMatch || !fDecl.returnType.equals(fInherit.returnType)) {
                                errors.semError(declId, "Method overridden with different type signature: %s", declName);
                            }
                        }
                        break;
                    }
                    currParent = parentMap.get(currParent);
                }
                sym.put(declName, declType);
            }
        }

        sym = sym.getParent();
        currentClassName = null;
        return new ClassValueType(className);
    }


}

# CS 164: Programming Assignment 2

[ChocoPy Specification]: https://sites.google.com/berkeley.edu/cs164-sp26/chocopy?authuser=1

Note: Users running Windows should replace the colon (`:`) with a semicolon (`;`) in the classpath argument for all command listed below.

## Getting started

Run the following command to build your semantic analysis, and then run all the provided tests:

    mvn clean package

    java -cp "chocopy-ref.jar:target/assignment.jar" chocopy.ChocoPy \
        --pass=.s --dir src/test/data/pa2/sample/ --test


In the starter code, only two tests should pass. Your objective is to implement a semantic analysis that passes all the provided tests and meets the assignment specifications.

You can also run the semantic analysis on one input file at at time. In general, running the semantic analysis on a ChocoPy program is a two-step process. First, run the reference parser to get an AST JSON:


    java -cp "chocopy-ref.jar:target/assignment.jar" chocopy.ChocoPy \
        --pass=r <chocopy_input_file> --out <ast_json_file> 


Second, run the semantic analysis on the AST JSON to get a typed AST JSON:

    java -cp "chocopy-ref.jar:target/assignment.jar" chocopy.ChocoPy \
        -pass=.s  <ast_json_file> --out <typed_ast_json_file>


The `src/tests/data/pa2/sample` directory already contains the AST JSONs for the test programs (with extension `.ast`); therefore, you can skip the first step for the sample test programs.

To observe the output of the reference implementation of the semantic analysis, replace the second step with the following command:


    java -cp "chocopy-ref.jar:target/assignment.jar" chocopy.ChocoPy \
        --pass=.r <ast_json_file> --out <typed_ast_json_file>


In either step, you can omit the `--out <output_file>` argument to have the JSON be printed to standard output instead.

You can combine AST generation by the reference parser with your 
semantic analysis as well:

    java -cp "chocopy-ref.jar:target/assignment.jar" chocopy.ChocoPy \
        --pass=rs <chocopy_input_file> --out <typed_ast_json_file>


## Assignment specifications

See the PA2 specification on the course
website for a detailed specification of the assignment.

Refer to the ChocoPy Specification on the CS164 web site
for the specification of the ChocoPy language. 

## Receiving updates to this repository

Add the `upstream` repository remotes (you only need to do this once in your local clone):

    git remote add upstream https://github.com/cs164-spring-2026/pa2-chocopy-semantic-analysis.git


To sync with updates upstream:

    git pull upstream master

## Submission writeup

Team member 1: Mark Zaydman

Team member 2: Ahan Singhal

1. How many passes does your semantic analysis perform over the AST? List the names of these passes with their class names and briefly explain the purpose of each pass.


Our semantic analysis performs two main passes over the abstract syntax tree (AST).

Pass 1: DeclarationAnalyzer 

Purpose: This pass traverses the AST to analyze declarations (such as global variables, classes, and functions) and constructs the initial typing environment by collecting these definitions into a symbol table. This ensures that all globally visible symbols are mapped and available before any expressions are evaluated.

Pass 2: TypeChecker

Purpose: This pass iterates over the AST a second time, utilizing the symbol table built by the DeclarationAnalyzer to type-check expressions and statements. It assigns inferred types to expression nodes and attaches error messages to the AST if it encounters violations of ChocoPy's typing rules.

2. What was the hardest component to implement in this assignment? Why was it challenging?

The hardest component to implement was correctly handling the type annotations for member expressions within method calls, which was causing my last two test cases to fail (ast_coverage.py.ast and class_def_methods.py.ast).

It was highly challenging because the JSON shape mismatches against the reference compiler were hard to spot. I eventually discovered that MemberExpr nodes nested inside MethodCallExpr nodes were not receiving their proper inferred FuncType in the output typed AST. To fix this, I had to make two key adjustments:

Changing analyze(MemberExpr) to explicitly check that member expressions properly resolve and return attribute types directly from the class hierarchy.

Changed analyze(MethodCallExpr) to explicitly assign the type to the method's member expression using expr.method.setInferredType(methodType);.

This was difficult because it required looking into how AST nodes interact during method dispatch and recognizing that the method selector itself requires an explicit FuncType annotation.

3. When type checking ill-typed expressions, why is it important to recover by inferring the most specific type? What is the problem if we simply infer the type object for every ill-typed expression? Explain your answer with the help of examples in the student_contributed/bad_types.py test.

It is important to infer the most specific type possible during error recovery because it prevents a single type error from cascading into multiple, confusing secondary errors throughout the rest of the expression. 
If we simply inferred the type object for every ill-typed expression, the compiler would immediately fail any subsequent type checks that rely on that expression evaluating to a specific type, masking the true cause of the error.

Example from bad_types.py:
Consider an expression that attempts to perform a binary addition where one operand is an integer and the other is a string, and then uses that result to index a list: my_list[1 + "str"].

Most Specific Type: When evaluating 1 + "str", we report a type-checking error. However, because at least one operand is an int, we still infer the type int for the entire addition expression. Because the addition is considered an int, the outer IndexExpr (my_list[...]) can still type-check successfully, and only the single root error is reported.

Inferring object: If we instead recovered by assigning object to 1 + "str", the outer list-select operation would also fail because list indices must evaluate to int. This would generate a second, misleading error message stating that the list index is of type object, without directly pointing to the issue.
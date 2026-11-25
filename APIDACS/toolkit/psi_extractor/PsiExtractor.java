package apidacs.psi;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.*;

/**
 * Passive Kotlin PSI AST Extractor for APIDACS.
 * Parses .kt and .kts files using kotlin-compiler-embeddable without executing source code.
 */
public class PsiExtractor {

    private static final Set<String> LOGGING_CALLEES = new HashSet<>(Arrays.asList(
        "println", "print", "Log.d", "Log.i", "Log.w", "Log.e", "Log.v", "Log.wtf",
        "d", "i", "w", "e", "v", "wtf", "logger", "log", "debug", "info", "warn", "error"
    ));

    private static final Set<String> LOG_RECEIVERS = new HashSet<>(Arrays.asList(
        "Log", "android.util.Log", "System.out", "System.err", "Timber", "Logger"
    ));

    private static final Set<String> ASSERTION_CALLEES = new HashSet<>(Arrays.asList(
        "assertEquals", "assertNotEquals", "assertTrue", "assertFalse", "assertNull",
        "assertNotNull", "assertThrows", "assertArrayEquals", "assertSame", "assertNotSame",
        "assertContains", "assertIs", "assertContentEquals", "fail", "verify",
        "assertIsDisplayed", "assertExists", "assertTextEquals", "performClick", "assert"
    ));

    private static final Set<String> COMPOSE_UI_CONTROLS = new HashSet<>(Arrays.asList(
        "Button", "OutlinedButton", "TextButton", "FilledTonalButton", "ElevatedButton",
        "IconButton", "FilledIconButton", "OutlinedIconButton", "FloatingActionButton",
        "ExtendedFloatingActionButton", "SmallFloatingActionButton", "Slider", "RangeSlider",
        "Switch", "Checkbox", "RadioButton", "HardwareChipToggle", "NavigationBarItem",
        "NavigationRailItem", "DropdownMenuItem", "Tab", "IconToggleButton",
        "hardwareButton", "JButton", "JSlider", "JCheckBox", "JToggleButton", "JComboBox"
    ));

    private static final Set<String> LITERAL_OR_CONSTANT_PREFIXES = new HashSet<>(Arrays.asList(
        "true", "false", "null"
    ));

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: PsiExtractor <file_list.txt | file1.kt ...>");
            System.exit(2);
        }

        List<String> files = new ArrayList<>();
        if (args.length == 2 && "--manifest".equals(args[0])) {
            try (BufferedReader br = new BufferedReader(new FileReader(args[1], StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        files.add(trimmed);
                    }
                }
            }
        } else {
            Collections.addAll(files, args);
        }

        Disposable disposable = Disposer.newDisposable("apidacs-psi");
        try {
            CompilerConfiguration config = new CompilerConfiguration();
            org.jetbrains.kotlin.cli.FrontendConfigurationKeysKt.setExtensionsStorage(
                config, new org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar.ExtensionStorage()
            );
            KotlinCoreEnvironment env = KotlinCoreEnvironment.createForProduction(
                disposable, config, EnvironmentConfigFiles.JVM_CONFIG_FILES
            );
            KtPsiFactory psiFactory = new KtPsiFactory(env.getProject(), false);

            StringBuilder out = new StringBuilder();
            out.append("[\n");
            for (int i = 0; i < files.size(); i++) {
                String pathStr = files.get(i);
                Map<String, Object> record = parseOneFile(psiFactory, pathStr);
                out.append(toJson(record));
                if (i < files.size() - 1) {
                    out.append(",");
                }
                out.append("\n");
            }
            out.append("]\n");
            System.out.print(out.toString());
        } finally {
            Disposer.dispose(disposable);
        }
    }

    private static Map<String, Object> parseOneFile(KtPsiFactory factory, String pathStr) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", pathStr);
        try {
            String content = Files.readString(Path.of(pathStr), StandardCharsets.UTF_8);
            String fileName = new File(pathStr).getName();
            KtFile ktFile = factory.createFile(fileName, content);
            Document doc = ktFile.getViewProvider().getDocument();

            String pkg = "";
            if (ktFile.getPackageDirective() != null) {
                pkg = ktFile.getPackageDirective().getFqName().asString();
            }
            result.put("package", pkg);

            List<Map<String, Object>> imports = new ArrayList<>();
            for (KtImportDirective imp : ktFile.getImportDirectives()) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("fqName", imp.getImportedFqName() != null ? imp.getImportedFqName().asString() : "");
                im.put("isAllUnder", imp.isAllUnder());
                im.put("alias", imp.getAliasName() != null ? imp.getAliasName() : "");
                im.put("line", lineOf(doc, imp));
                imports.add(im);
            }
            result.put("imports", imports);

            List<Map<String, Object>> types = new ArrayList<>();
            List<Map<String, Object>> functions = new ArrayList<>();
            List<Map<String, Object>> variables = new ArrayList<>();
            List<Map<String, Object>> suppressions = new ArrayList<>();
            List<Map<String, Object>> reflectionMarkers = new ArrayList<>();
            List<Map<String, Object>> uiControls = new ArrayList<>();

            // File-level annotations (e.g. @file:Suppress(...))
            for (KtAnnotationEntry ann : ktFile.getAnnotationEntries()) {
                collectSuppressionIfAny(ann, doc, "<file>", suppressions);
            }

            // Walk declarations recursively preserving owner context
            for (KtDeclaration decl : ktFile.getDeclarations()) {
                visitDeclaration(
                    decl, pkg, "", "", doc,
                    types, functions, variables, suppressions, reflectionMarkers, uiControls
                );
            }

            final String finalPkg = pkg;
            // Also scan entire file for reflection markers if top-level or script expressions
            ktFile.accept(new KtTreeVisitorVoid() {
                @Override
                public void visitDotQualifiedExpression(KtDotQualifiedExpression expression) {
                    super.visitDotQualifiedExpression(expression);
                    String txt = safeText(expression);
                    if (txt.contains("Class.forName") || txt.contains(".javaClass")
                        || txt.contains("getDeclaredField") || txt.contains("getDeclaredMethod")
                        || txt.contains("ServiceLoader.load") || txt.contains("MethodHandles")) {
                        Map<String, Object> rm = new LinkedHashMap<>();
                        rm.put("kind", "reflection_call");
                        rm.put("expression", truncate(txt, 140));
                        rm.put("line", lineOf(doc, expression));
                        reflectionMarkers.add(rm);
                    }
                }

                @Override
                public void visitClassLiteralExpression(KtClassLiteralExpression expression) {
                    super.visitClassLiteralExpression(expression);
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("kind", "class_literal");
                    rm.put("expression", truncate(safeText(expression), 100));
                    rm.put("line", lineOf(doc, expression));
                    reflectionMarkers.add(rm);
                }

                @Override
                public void visitScript(KtScript script) {
                    super.visitScript(script);
                    // Handle .kts top-level expressions / calls (dependencies { ... }, plugins { ... })
                    for (KtDeclaration sDecl : script.getDeclarations()) {
                        PsiExtractor.visitDeclaration(
                            sDecl, finalPkg, "<script>", "", doc,
                            types, functions, variables, suppressions, reflectionMarkers, uiControls
                        );
                    }
                }
            });

            // Script calls for .kts build files
            if (pathStr.endsWith(".kts")) {
                List<Map<String, Object>> scriptCalls = new ArrayList<>();
                ktFile.accept(new KtTreeVisitorVoid() {
                    @Override
                    public void visitCallExpression(KtCallExpression expression) {
                        super.visitCallExpression(expression);
                        String callee = expression.getCalleeExpression() != null ? expression.getCalleeExpression().getText() : "";
                        List<String> argTexts = new ArrayList<>();
                        for (KtValueArgument va : expression.getValueArguments()) {
                            argTexts.add(truncate(safeText(va), 140));
                        }
                        Map<String, Object> sc = new LinkedHashMap<>();
                        sc.put("callee", callee);
                        sc.put("args", argTexts);
                        sc.put("line", lineOf(doc, expression));
                        scriptCalls.add(sc);
                    }
                });
                result.put("scriptCalls", scriptCalls);
            }

            result.put("types", types);
            result.put("functions", functions);
            result.put("variables", variables);
            result.put("suppressions", suppressions);
            result.put("reflectionMarkers", deduplicateByLineAndExpr(reflectionMarkers));
            result.put("uiControls", uiControls);
            result.put("parseStatus", "OK");
            result.put("parseError", "");
        } catch (Throwable t) {
            result.put("parseStatus", "ERROR");
            result.put("parseError", t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            result.put("types", Collections.emptyList());
            result.put("functions", Collections.emptyList());
            result.put("variables", Collections.emptyList());
            result.put("suppressions", Collections.emptyList());
            result.put("reflectionMarkers", Collections.emptyList());
            result.put("uiControls", Collections.emptyList());
        }
        return result;
    }

    private static void visitDeclaration(
        KtDeclaration decl,
        String pkg,
        String ownerType,
        String ownerFunc,
        Document doc,
        List<Map<String, Object>> types,
        List<Map<String, Object>> functions,
        List<Map<String, Object>> variables,
        List<Map<String, Object>> suppressions,
        List<Map<String, Object>> reflectionMarkers,
        List<Map<String, Object>> uiControls
    ) {
        if (decl instanceof KtClassOrObject) {
            KtClassOrObject cls = (KtClassOrObject) decl;
            String clsName = cls.getName() != null ? cls.getName() : "<anonymous>";
            String basePrefix = !ownerType.isEmpty() ? ownerType : pkg;
            String qName = basePrefix.isEmpty() ? clsName : (basePrefix + "." + clsName);

            List<String> anns = getAnnotations(cls, doc, qName, suppressions);
            List<String> mods = getModifiers(cls);
            String vis = extractVisibility(mods);
            String kind = "class";
            if (cls instanceof KtEnumEntry) {
                kind = "enum_entry";
            } else if (cls instanceof KtObjectDeclaration) {
                kind = ((KtObjectDeclaration) cls).isCompanion() ? "companion_object" : "object";
            } else if (cls instanceof KtClass) {
                KtClass kc = (KtClass) cls;
                if (kc.isInterface()) kind = "interface";
                else if (kc.isEnum()) kind = "enum";
                else if (kc.isData()) kind = "data_class";
                else if (kc.isSealed()) kind = "sealed_class";
                else if (kc.isAnnotation()) kind = "annotation_class";
            }

            List<String> supertypes = new ArrayList<>();
            for (KtSuperTypeListEntry entry : cls.getSuperTypeListEntries()) {
                if (entry.getTypeReference() != null) {
                    String st = cleanWs(entry.getTypeReference().getText());
                    if (!st.isEmpty()) supertypes.add(st);
                }
            }

            Map<String, Object> tMap = new LinkedHashMap<>();
            tMap.put("name", clsName);
            tMap.put("qualifiedName", qName);
            tMap.put("kind", kind);
            tMap.put("visibility", vis);
            tMap.put("supertypes", supertypes);
            tMap.put("annotations", anns);
            tMap.put("modifiers", mods);
            tMap.put("lineStart", lineOf(doc, cls));
            tMap.put("lineEnd", endLineOf(doc, cls));
            tMap.put("isData", kind.equals("data_class"));
            tMap.put("isSealed", mods.contains("sealed"));
            tMap.put("serializationContract", anns.contains("Serializable") || supertypes.contains("Serializable") || supertypes.contains("Parcelable"));
            types.add(tMap);

            // Primary constructor parameters (val/var properties vs plain ctor params)
            for (KtParameter param : cls.getPrimaryConstructorParameters()) {
                String pName = param.getName() != null ? param.getName() : "";
                if (pName.isEmpty()) continue;
                String pType = param.getTypeReference() != null ? cleanWs(param.getTypeReference().getText()) : "Unknown";
                String defVal = param.getDefaultValue() != null ? truncate(safeText(param.getDefaultValue()), 120) : "";
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("name", pName);
                v.put("qualifiedName", qName + "." + pName);
                v.put("ownerType", qName);
                v.put("ownerFunction", "<init>");
                v.put("scope", param.hasValOrVar() ? "member" : "parameter");
                v.put("kind", param.hasValOrVar() ? (param.isMutable() ? "var" : "val") : "param");
                v.put("declaredType", pType);
                v.put("visibility", param.hasValOrVar() ? extractVisibility(getModifiers(param)) : vis);
                v.put("mutability", param.isMutable() ? "var" : "val");
                v.put("initialValue", defVal);
                v.put("isHardcodedLiteral", isHardcodedLiteralExpr(param.getDefaultValue()));
                v.put("line", lineOf(doc, param));
                v.put("annotations", getAnnotations(param, doc, qName + "." + pName, suppressions));
                variables.add(v);
            }

            for (KtDeclaration member : cls.getDeclarations()) {
                visitDeclaration(
                    member, pkg, qName, "", doc,
                    types, functions, variables, suppressions, reflectionMarkers, uiControls
                );
            }
        } else if (decl instanceof KtNamedFunction) {
            KtNamedFunction fn = (KtNamedFunction) decl;
            String fnName = fn.getName() != null ? fn.getName() : "<lambda>";
            String basePrefix = !ownerType.isEmpty() ? ownerType : pkg;
            String qName = basePrefix.isEmpty() ? fnName : (basePrefix + "." + fnName);

            List<String> anns = getAnnotations(fn, doc, qName, suppressions);
            List<String> mods = getModifiers(fn);
            String vis = extractVisibility(mods);
            String retType = fn.getTypeReference() != null ? cleanWs(fn.getTypeReference().getText()) : "InferredOrUnit";

            List<Map<String, Object>> paramsList = new ArrayList<>();
            List<String> paramNames = new ArrayList<>();
            for (KtParameter p : fn.getValueParameters()) {
                String pName = p.getName() != null ? p.getName() : "";
                String pType = p.getTypeReference() != null ? cleanWs(p.getTypeReference().getText()) : "Unknown";
                String defVal = p.getDefaultValue() != null ? truncate(safeText(p.getDefaultValue()), 120) : "";
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", pName);
                pm.put("type", pType);
                pm.put("defaultValue", defVal);
                pm.put("line", lineOf(doc, p));
                paramsList.add(pm);
                if (!pName.isEmpty()) paramNames.add(pName);

                // Register function parameters as tracked variables too
                if (!pName.isEmpty()) {
                    Map<String, Object> pv = new LinkedHashMap<>();
                    pv.put("name", pName);
                    pv.put("qualifiedName", qName + "#" + pName);
                    pv.put("ownerType", ownerType);
                    pv.put("ownerFunction", qName);
                    pv.put("scope", "parameter");
                    pv.put("kind", "param");
                    pv.put("declaredType", pType);
                    pv.put("visibility", "local");
                    pv.put("mutability", "val");
                    pv.put("initialValue", defVal);
                    pv.put("isHardcodedLiteral", isHardcodedLiteralExpr(p.getDefaultValue()));
                    pv.put("line", lineOf(doc, p));
                    pv.put("annotations", getAnnotations(p, doc, qName + "#" + pName, suppressions));
                    variables.add(pv);
                }
            }

            // Analyze function body
            BodyAnalysis body = analyzeFunctionBody(fn, qName, ownerType, doc, variables, suppressions, uiControls);

            boolean isTest = anns.contains("Test") || anns.contains("org.junit.Test");
            String skipReason = "";
            if (anns.contains("Ignore") || anns.contains("Disabled")) {
                skipReason = "@" + (anns.contains("Ignore") ? "Ignore" : "Disabled");
            }
            boolean isEmpty = isFunctionBodyEmpty(fn);

            Map<String, Object> fMap = new LinkedHashMap<>();
            fMap.put("name", fnName);
            fMap.put("qualifiedName", qName);
            fMap.put("ownerType", ownerType);
            fMap.put("signature", buildSignature(fnName, paramsList, retType));
            fMap.put("visibility", vis);
            fMap.put("params", paramsList);
            fMap.put("returnType", retType);
            fMap.put("annotations", anns);
            fMap.put("modifiers", mods);
            fMap.put("lineStart", lineOf(doc, fn));
            fMap.put("lineEnd", endLineOf(doc, fn));
            fMap.put("isOverride", mods.contains("override"));
            fMap.put("isComposable", anns.contains("Composable"));
            fMap.put("isTest", isTest);
            fMap.put("skipReason", skipReason);
            fMap.put("isEmpty", isEmpty);
            fMap.put("hasBody", fn.getBodyExpression() != null);
            fMap.put("assertionCount", body.assertionCount);
            fMap.put("usesMocks", body.usesMocks);
            fMap.put("hasSideEffects", body.hasSideEffects);
            fMap.put("calls", body.calls);
            fMap.put("assignments", body.assignments);
            fMap.put("reads", body.reads);
            fMap.put("ignoredParams", computeIgnoredParams(paramNames, body));
            functions.add(fMap);
        } else if (decl instanceof KtProperty) {
            KtProperty prop = (KtProperty) decl;
            String pName = prop.getName() != null ? prop.getName() : "";
            if (pName.isEmpty()) return;
            String basePrefix = !ownerFunc.isEmpty() ? ownerFunc : (!ownerType.isEmpty() ? ownerType : pkg);
            String qName = basePrefix.isEmpty() ? pName : (basePrefix + "." + pName);
            List<String> anns = getAnnotations(prop, doc, qName, suppressions);
            List<String> mods = getModifiers(prop);
            String scope = !ownerFunc.isEmpty() ? "local" : (!ownerType.isEmpty() ? "member" : "top_level");
            String kind = mods.contains("const") ? "const" : (prop.isVar() ? "var" : "val");
            String dType = prop.getTypeReference() != null ? cleanWs(prop.getTypeReference().getText()) : inferTypeFromInit(prop.getInitializer());
            String initText = prop.getInitializer() != null ? truncate(safeText(prop.getInitializer()), 160) : "";

            Map<String, Object> v = new LinkedHashMap<>();
            v.put("name", pName);
            v.put("qualifiedName", qName);
            v.put("ownerType", ownerType);
            v.put("ownerFunction", ownerFunc);
            v.put("scope", scope);
            v.put("kind", kind);
            v.put("declaredType", dType);
            v.put("visibility", extractVisibility(mods));
            v.put("mutability", prop.isVar() ? "var" : "val");
            v.put("initialValue", initText);
            v.put("isHardcodedLiteral", isHardcodedLiteralExpr(prop.getInitializer()));
            v.put("line", lineOf(doc, prop));
            v.put("annotations", anns);
            variables.add(v);
        }
    }

    private static class BodyAnalysis {
        List<Map<String, Object>> calls = new ArrayList<>();
        List<Map<String, Object>> assignments = new ArrayList<>();
        List<Map<String, Object>> reads = new ArrayList<>();
        Set<String> referencedIdentifiers = new HashSet<>();
        int assertionCount = 0;
        boolean usesMocks = false;
        boolean hasSideEffects = false;
    }

    private static BodyAnalysis analyzeFunctionBody(
        KtNamedFunction fn,
        String qFuncName,
        String ownerType,
        Document doc,
        List<Map<String, Object>> variables,
        List<Map<String, Object>> suppressions,
        List<Map<String, Object>> uiControls
    ) {
        BodyAnalysis ba = new BodyAnalysis();
        KtExpression body = fn.getBodyExpression();
        if (body == null) return ba;

        body.accept(new KtTreeVisitorVoid() {
            @Override
            public void visitProperty(KtProperty property) {
                // Local variable inside function
                String pName = property.getName() != null ? property.getName() : "";
                if (!pName.isEmpty()) {
                    List<String> anns = getAnnotations(property, doc, qFuncName + "#" + pName, suppressions);
                    List<String> mods = getModifiers(property);
                    String dType = property.getTypeReference() != null
                        ? cleanWs(property.getTypeReference().getText())
                        : inferTypeFromInit(property.getInitializer());
                    String initText = property.getInitializer() != null ? truncate(safeText(property.getInitializer()), 160) : "";
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("name", pName);
                    v.put("qualifiedName", qFuncName + "#" + pName);
                    v.put("ownerType", ownerType);
                    v.put("ownerFunction", qFuncName);
                    v.put("scope", "local");
                    v.put("kind", property.isVar() ? "var" : "val");
                    v.put("declaredType", dType);
                    v.put("visibility", "local");
                    v.put("mutability", property.isVar() ? "var" : "val");
                    v.put("initialValue", initText);
                    v.put("isHardcodedLiteral", isHardcodedLiteralExpr(property.getInitializer()));
                    v.put("line", lineOf(doc, property));
                    v.put("annotations", anns);
                    variables.add(v);
                }
                super.visitProperty(property);
            }

            @Override
            public void visitBinaryExpression(KtBinaryExpression expression) {
                if (expression.getOperationToken() == KtTokens.EQ
                    || expression.getOperationToken() == KtTokens.PLUSEQ
                    || expression.getOperationToken() == KtTokens.MINUSEQ
                    || expression.getOperationToken() == KtTokens.MULTEQ
                    || expression.getOperationToken() == KtTokens.DIVEQ) {
                    String lhs = expression.getLeft() != null ? cleanWs(expression.getLeft().getText()) : "";
                    String rhs = expression.getRight() != null ? truncate(safeText(expression.getRight()), 140) : "";
                    Map<String, Object> asg = new LinkedHashMap<>();
                    asg.put("lhs", lhs);
                    asg.put("rhs", rhs);
                    asg.put("rhsIdentifiers", extractIdentifiers(expression.getRight()));
                    asg.put("isHardcoded", isHardcodedLiteralExpr(expression.getRight()));
                    asg.put("line", lineOf(doc, expression));
                    ba.assignments.add(asg);
                    ba.hasSideEffects = true;
                }
                super.visitBinaryExpression(expression);
            }

            @Override
            public void visitCallExpression(KtCallExpression expression) {
                String callee = expression.getCalleeExpression() != null
                    ? cleanWs(expression.getCalleeExpression().getText())
                    : "";
                String receiver = "";
                PsiElement parent = expression.getParent();
                if (parent instanceof KtDotQualifiedExpression) {
                    KtDotQualifiedExpression dq = (KtDotQualifiedExpression) parent;
                    if (dq.getSelectorExpression() == expression) {
                        receiver = cleanWs(dq.getReceiverExpression().getText());
                    }
                } else if (parent instanceof KtSafeQualifiedExpression) {
                    KtSafeQualifiedExpression sq = (KtSafeQualifiedExpression) parent;
                    if (sq.getSelectorExpression() == expression) {
                        receiver = cleanWs(sq.getReceiverExpression().getText());
                    }
                }

                boolean isLogging = LOGGING_CALLEES.contains(callee)
                    || LOG_RECEIVERS.contains(receiver)
                    || (receiver.endsWith("Log") && LOGGING_CALLEES.contains(callee));

                if (ASSERTION_CALLEES.contains(callee) || callee.startsWith("assert")) {
                    ba.assertionCount++;
                }
                if (callee.equals("mock") || callee.equals("mockk") || callee.equals("every")
                    || callee.equals("coEvery") || callee.startsWith("Fake")) {
                    ba.usesMocks = true;
                }

                List<Map<String, Object>> args = new ArrayList<>();
                for (KtValueArgument va : expression.getValueArguments()) {
                    Map<String, Object> am = new LinkedHashMap<>();
                    String argName = va.getArgumentName() != null ? va.getArgumentName().getAsName().asString() : "";
                    String argText = va.getArgumentExpression() != null ? truncate(safeText(va.getArgumentExpression()), 120) : "";
                    am.put("name", argName);
                    am.put("text", argText);
                    am.put("identifiers", extractIdentifiers(va.getArgumentExpression()));
                    am.put("isHardcoded", isHardcodedLiteralExpr(va.getArgumentExpression()));
                    args.add(am);
                }

                // Detect UI controls (Compose or Swing)
                if (COMPOSE_UI_CONTROLS.contains(callee)) {
                    Map<String, Object> ui = extractUiControl(callee, expression, qFuncName, doc);
                    if (ui != null) {
                        uiControls.add(ui);
                    }
                }

                Map<String, Object> call = new LinkedHashMap<>();
                call.put("callee", callee);
                call.put("receiver", receiver);
                call.put("isLogging", isLogging);
                call.put("args", args);
                call.put("returnUsed", isReturnValueUsed(expression));
                call.put("line", lineOf(doc, expression));
                ba.calls.add(call);

                if (receiver.contains("_uiState") || callee.equals("update") || callee.equals("emit")) {
                    ba.hasSideEffects = true;
                }

                super.visitCallExpression(expression);
            }

            @Override
            public void visitSimpleNameExpression(KtSimpleNameExpression expression) {
                String name = expression.getReferencedName();
                if (name != null && !name.isEmpty()) {
                    // Check parent to distinguish LHS assignment target vs read
                    PsiElement parent = expression.getParent();
                    boolean isLhs = false;
                    if (parent instanceof KtBinaryExpression) {
                        KtBinaryExpression be = (KtBinaryExpression) parent;
                        if (be.getOperationToken() == KtTokens.EQ && be.getLeft() == expression) {
                            isLhs = true;
                        }
                    }
                    if (!isLhs) {
                        ba.referencedIdentifiers.add(name);
                        String context = classifyReadContext(expression);
                        String qualifier = "";
                        if (parent instanceof KtDotQualifiedExpression) {
                            KtDotQualifiedExpression dq = (KtDotQualifiedExpression) parent;
                            if (dq.getSelectorExpression() == expression) {
                                qualifier = cleanWs(dq.getReceiverExpression().getText());
                            }
                        }
                        Map<String, Object> rd = new LinkedHashMap<>();
                        rd.put("name", name);
                        rd.put("qualifier", qualifier);
                        rd.put("context", context);
                        rd.put("line", lineOf(doc, expression));
                        ba.reads.add(rd);
                    }
                }
                super.visitSimpleNameExpression(expression);
            }
        });

        return ba;
    }

    private static Map<String, Object> extractUiControl(
        String callee, KtCallExpression call, String ownerFunc, Document doc
    ) {
        Map<String, Object> ui = new LinkedHashMap<>();
        String label = "";
        String testTag = "";
        String handlerName = "";
        List<String> backendCalls = new ArrayList<>();

        for (KtValueArgument va : call.getValueArguments()) {
            String argName = va.getArgumentName() != null ? va.getArgumentName().getAsName().asString() : "";
            KtExpression expr = va.getArgumentExpression();
            String txt = expr != null ? cleanWs(expr.getText()) : "";
            if ("modifier".equals(argName) && txt.contains("testTag")) {
                int idx = txt.indexOf("testTag(");
                if (idx >= 0) {
                    int end = txt.indexOf(")", idx);
                    if (end > idx) {
                        testTag = txt.substring(idx + 8, end).replace("\"", "").trim();
                    }
                }
            }
            if (argName.equals("onClick") || argName.equals("onValueChange") || argName.equals("onCheckedChange")
                || argName.equals("onSelected") || argName.equals("text") || argName.equals("label")) {
                if (argName.equals("text") || argName.equals("label")) {
                    label = truncate(txt.replace("\"", ""), 60);
                } else {
                    handlerName = argName;
                    if (expr != null) {
                        extractCallsInside(expr, backendCalls);
                        if (backendCalls.isEmpty() && !(expr instanceof KtLambdaExpression) && !txt.isEmpty()) {
                            backendCalls.add("callback:" + truncate(txt, 60));
                        }
                    }
                }
            } else if (argName.isEmpty() && (expr instanceof KtLambdaExpression || expr instanceof KtCallableReferenceExpression)) {
                handlerName = "positional_callback";
                extractCallsInside(expr, backendCalls);
                if (backendCalls.isEmpty() && !(expr instanceof KtLambdaExpression) && !txt.isEmpty()) {
                    backendCalls.add("callback:" + truncate(txt, 60));
                }
            }
        }

        // Trailing lambda argument (Compose body or onClick trailing lambda)
        for (KtLambdaArgument la : call.getLambdaArguments()) {
            KtExpression lexpr = la.getLambdaExpression();
            if (lexpr != null) {
                extractCallsInside(lexpr, backendCalls);
                // Find Text("...") inside trailing lambda for label
                if (label.isEmpty()) {
                    lexpr.accept(new KtTreeVisitorVoid() {
                        @Override
                        public void visitCallExpression(KtCallExpression inner) {
                            String ic = inner.getCalleeExpression() != null ? inner.getCalleeExpression().getText() : "";
                            if ("Text".equals(ic) && !inner.getValueArguments().isEmpty()) {
                                String t = cleanWs(inner.getValueArguments().get(0).getText());
                                // Only simple literal text
                                if (t.startsWith("\"") && t.length() < 64) {
                                    ui.put("_foundText", t.replace("\"", ""));
                                }
                            }
                            super.visitCallExpression(inner);
                        }
                    });
                }
            }
        }
        if (label.isEmpty() && ui.containsKey("_foundText")) {
            label = String.valueOf(ui.remove("_foundText"));
        } else {
            ui.remove("_foundText");
        }
        if (label.isEmpty() && !call.getValueArguments().isEmpty()) {
            String first = cleanWs(call.getValueArguments().get(0).getText());
            if (first.startsWith("\"")) label = truncate(first.replace("\"", ""), 60);
        }

        ui.put("screenName", ownerFunc);
        ui.put("componentType", callee);
        ui.put("label", label.isEmpty() ? (testTag.isEmpty() ? callee : testTag) : label);
        ui.put("testTag", testTag);
        ui.put("handlerName", handlerName.isEmpty() ? "trailing_lambda" : handlerName);
        ui.put("backendCalls", backendCalls);
        ui.put("line", lineOf(doc, call));
        return ui;
    }

    private static void extractCallsInside(PsiElement root, List<String> out) {
        root.accept(new KtTreeVisitorVoid() {
            @Override
            public void visitCallExpression(KtCallExpression expression) {
                String c = expression.getCalleeExpression() != null ? cleanWs(expression.getCalleeExpression().getText()) : "";
                if (!c.isEmpty() && !c.equals("Text") && !c.equals("Icon") && !c.equals("Spacer")) {
                    String full = c;
                    if (expression.getParent() instanceof KtDotQualifiedExpression) {
                        KtDotQualifiedExpression dq = (KtDotQualifiedExpression) expression.getParent();
                        if (dq.getSelectorExpression() == expression) {
                            full = cleanWs(dq.getReceiverExpression().getText()) + "." + c;
                        }
                    }
                    if (!out.contains(full)) out.add(full);
                }
                super.visitCallExpression(expression);
            }
        });
    }

    private static String classifyReadContext(KtSimpleNameExpression expr) {
        PsiElement cur = expr.getParent();
        while (cur != null) {
            if (cur instanceof KtCallExpression) {
                KtCallExpression ce = (KtCallExpression) cur;
                String callee = ce.getCalleeExpression() != null ? ce.getCalleeExpression().getText() : "";
                String recv = "";
                if (ce.getParent() instanceof KtDotQualifiedExpression) {
                    KtDotQualifiedExpression dq = (KtDotQualifiedExpression) ce.getParent();
                    if (dq.getSelectorExpression() == ce) {
                        recv = cleanWs(dq.getReceiverExpression().getText());
                    }
                }
                if (LOGGING_CALLEES.contains(callee) || LOG_RECEIVERS.contains(recv)) {
                    return "logging";
                }
                return "call_arg";
            }
            if (cur instanceof KtStringTemplateExpression) {
                // If inside a logging call, classify as logging
                PsiElement p = cur.getParent();
                while (p != null) {
                    if (p instanceof KtCallExpression) {
                        KtCallExpression ce = (KtCallExpression) p;
                        String callee = ce.getCalleeExpression() != null ? ce.getCalleeExpression().getText() : "";
                        if (LOGGING_CALLEES.contains(callee)) return "logging";
                        break;
                    }
                    if (p instanceof KtNamedFunction) break;
                    p = p.getParent();
                }
                return "string_template";
            }
            if (cur instanceof KtReturnExpression) return "return";
            if (cur instanceof KtIfExpression || cur instanceof KtWhenExpression || cur instanceof KtWhileExpression) {
                return "control_flow";
            }
            if (cur instanceof KtBinaryExpression) {
                KtBinaryExpression be = (KtBinaryExpression) cur;
                if (be.getOperationToken() == KtTokens.EQ) return "assignment_rhs";
                return "computation";
            }
            if (cur instanceof KtProperty) return "initializer";
            if (cur instanceof KtNamedFunction) break;
            cur = cur.getParent();
        }
        return "read";
    }

    private static boolean isReturnValueUsed(KtCallExpression call) {
        PsiElement p = call.getParent();
        if (p instanceof KtDotQualifiedExpression || p instanceof KtSafeQualifiedExpression) {
            p = p.getParent();
        }
        return !(p instanceof KtBlockExpression);
    }

    private static boolean isFunctionBodyEmpty(KtNamedFunction fn) {
        KtExpression body = fn.getBodyExpression();
        if (body == null) return false; // abstract or interface method
        if (body instanceof KtBlockExpression) {
            KtBlockExpression blk = (KtBlockExpression) body;
            return blk.getStatements().isEmpty();
        }
        return false;
    }

    private static List<String> computeIgnoredParams(List<String> paramNames, BodyAnalysis ba) {
        List<String> ignored = new ArrayList<>();
        for (String p : paramNames) {
            if (!ba.referencedIdentifiers.contains(p)) {
                ignored.add(p);
            }
        }
        return ignored;
    }

    private static List<String> extractIdentifiers(KtExpression expr) {
        List<String> ids = new ArrayList<>();
        if (expr == null) return ids;
        expr.accept(new KtTreeVisitorVoid() {
            @Override
            public void visitSimpleNameExpression(KtSimpleNameExpression expression) {
                String n = expression.getReferencedName();
                if (n != null && !n.isEmpty() && !ids.contains(n)) {
                    ids.add(n);
                }
                super.visitSimpleNameExpression(expression);
            }
        });
        return ids;
    }

    private static boolean isHardcodedLiteralExpr(KtExpression expr) {
        if (expr == null) return false;
        if (expr instanceof KtConstantExpression || expr instanceof KtStringTemplateExpression) {
            if (expr instanceof KtStringTemplateExpression) {
                return !((KtStringTemplateExpression) expr).hasInterpolation();
            }
            return true;
        }
        if (expr instanceof KtPrefixExpression) {
            KtPrefixExpression pe = (KtPrefixExpression) expr;
            return pe.getBaseExpression() instanceof KtConstantExpression;
        }
        String t = cleanWs(expr.getText());
        return LITERAL_OR_CONSTANT_PREFIXES.contains(t);
    }

    private static String inferTypeFromInit(KtExpression expr) {
        if (expr == null) return "Unknown";
        if (expr instanceof KtStringTemplateExpression) return "String";
        if (expr instanceof KtConstantExpression) {
            String t = expr.getText();
            if ("true".equals(t) || "false".equals(t)) return "Boolean";
            if (t.endsWith("f") || t.endsWith("F")) return "Float";
            if (t.endsWith("L")) return "Long";
            if (t.contains(".")) return "Double";
            if ("null".equals(t)) return "Any?";
            return "Int";
        }
        return "Inferred";
    }

    private static List<String> getAnnotations(
        KtAnnotated element, Document doc, String ownerSymbol, List<Map<String, Object>> suppressions
    ) {
        List<String> out = new ArrayList<>();
        for (KtAnnotationEntry ann : element.getAnnotationEntries()) {
            String shortName = ann.getShortName() != null ? ann.getShortName().asString() : "";
            if (!shortName.isEmpty()) {
                out.add(shortName);
                collectSuppressionIfAny(ann, doc, ownerSymbol, suppressions);
            }
        }
        return out;
    }

    private static void collectSuppressionIfAny(
        KtAnnotationEntry ann, Document doc, String ownerSymbol, List<Map<String, Object>> suppressions
    ) {
        String shortName = ann.getShortName() != null ? ann.getShortName().asString() : "";
        if ("Suppress".equals(shortName) || "SuppressLint".equals(shortName) || "SuppressWarnings".equals(shortName)) {
            List<String> rules = new ArrayList<>();
            if (ann.getValueArgumentList() != null) {
                for (KtValueArgument va : ann.getValueArgumentList().getArguments()) {
                    String txt = cleanWs(va.getText()).replace("\"", "");
                    if (!txt.isEmpty()) rules.add(txt);
                }
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("symbol", ownerSymbol);
            s.put("annotationText", truncate(cleanWs(ann.getText()), 180));
            s.put("rules", rules);
            s.put("line", lineOf(doc, ann));
            suppressions.add(s);
        }
    }

    private static List<String> getModifiers(KtModifierListOwner owner) {
        List<String> out = new ArrayList<>();
        KtModifierList ml = owner.getModifierList();
        if (ml == null) return out;
        for (KtModifierKeywordToken tok : Arrays.asList(
            KtTokens.PUBLIC_KEYWORD, KtTokens.PRIVATE_KEYWORD, KtTokens.PROTECTED_KEYWORD,
            KtTokens.INTERNAL_KEYWORD, KtTokens.OVERRIDE_KEYWORD, KtTokens.OPEN_KEYWORD,
            KtTokens.ABSTRACT_KEYWORD, KtTokens.SEALED_KEYWORD, KtTokens.DATA_KEYWORD,
            KtTokens.INLINE_KEYWORD, KtTokens.SUSPEND_KEYWORD, KtTokens.CONST_KEYWORD,
            KtTokens.COMPANION_KEYWORD, KtTokens.ENUM_KEYWORD, KtTokens.INNER_KEYWORD
        )) {
            if (ml.hasModifier(tok)) out.add(tok.getValue());
        }
        return out;
    }

    private static String extractVisibility(List<String> mods) {
        for (String v : Arrays.asList("private", "protected", "internal", "public")) {
            if (mods.contains(v)) return v;
        }
        return "public";
    }

    private static String buildSignature(String name, List<Map<String, Object>> params, String ret) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("(");
        for (int i = 0; i < params.size(); i++) {
            Map<String, Object> p = params.get(i);
            sb.append(p.get("name")).append(": ").append(p.get("type"));
            if (i < params.size() - 1) sb.append(", ");
        }
        sb.append("): ").append(ret);
        return sb.toString();
    }

    private static int lineOf(Document doc, PsiElement el) {
        if (doc == null || el == null) return 0;
        int offset = el.getTextRange().getStartOffset();
        if (offset < 0 || offset > doc.getTextLength()) return 0;
        return doc.getLineNumber(offset) + 1;
    }

    private static int endLineOf(Document doc, PsiElement el) {
        if (doc == null || el == null) return 0;
        int offset = Math.max(0, el.getTextRange().getEndOffset() - 1);
        if (offset > doc.getTextLength()) offset = doc.getTextLength();
        return doc.getLineNumber(offset) + 1;
    }

    private static String safeText(PsiElement el) {
        return el == null ? "" : el.getText();
    }

    private static String cleanWs(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String c = cleanWs(s);
        return c.length() <= max ? c : c.substring(0, max - 3) + "...";
    }

    private static List<Map<String, Object>> deduplicateByLineAndExpr(List<Map<String, Object>> items) {
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String key = item.get("line") + "::" + item.get("expression");
            if (seen.add(key)) out.add(item);
        }
        return out;
    }

    // Minimal deterministic JSON serializer
    @SuppressWarnings("unchecked")
    private static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof Boolean || obj instanceof Number) return obj.toString();
        if (obj instanceof String) {
            String s = (String) obj;
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                switch (ch) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                        else sb.append(ch);
                }
            }
            sb.append("\"");
            return sb.toString();
        }
        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(String.valueOf(e.getKey()))).append(":").append(toJson(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        return toJson(String.valueOf(obj));
    }
}

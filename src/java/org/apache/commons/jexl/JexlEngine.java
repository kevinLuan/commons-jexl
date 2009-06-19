/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.jexl;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.Map;
import java.util.Collections;
import java.util.HashMap;
import java.net.URL;
import java.net.URLConnection;
import org.apache.commons.logging.*;

import org.apache.commons.jexl.parser.ParseException;
import org.apache.commons.jexl.parser.Parser;
import org.apache.commons.jexl.parser.SimpleNode;
import org.apache.commons.jexl.parser.TokenMgrError;
import org.apache.commons.jexl.parser.ASTJexlScript;
import org.apache.commons.jexl.util.Introspector;
import org.apache.commons.jexl.util.introspection.Uberspect;

/**
 * <p>
 * Creates Expression and Script objects.
 * Determines the behavior of Expressions & Scripts during their evaluation wrt:
 *  - introspection
 *  - arithmetic & comparison
 *  - error reporting
 *  - logging
 * </p>
 */
public class JexlEngine {
    /**
     * The Uberspect & Arithmetic
     */
    protected final Uberspect uberspect;
    protected final Arithmetic arithmetic;
    /**
     * The Log to which all JexlEngine messages will be logged.
     */
    protected final Log LOG;
    /**
     * The singleton ExpressionFactory also holds a single instance of
     * {@link Parser}.
     * When parsing expressions, ExpressionFactory synchronizes on Parser.
     */
    protected final Parser parser = new Parser(new StringReader(";")); //$NON-NLS-1$

    /**
     * Whether expressions evaluated by this engine will throw exceptions or 
     * return null
     */
    boolean silent = true;

    /**
     *  The map of 'prefix:function' to object implementing the function.
     */
    protected Map<String,Object> functions = Collections.EMPTY_MAP;
    /**
     * ExpressionFactory & ScriptFactory need a singleton and this is the package
     * instance fulfilling that pattern.
     */
    static final JexlEngine DEFAULT = new JexlEngine();

    /**
     * Creates a default engine
     */
    public JexlEngine() {
        this(null, null, null, null);
    }

    /**
     * Creates a JEXL engine using the provided {@link Uberspect}, (@link Arithmetic) and logger.
     * @param uberspect to allow different introspection behaviour
     * @param arithmetic to allow different arithmetic behaviour
     * @param funcs an optional map of functions (@see setFunctions)
     * @param log the logger for various messages
     */
    public JexlEngine(Uberspect uberspect, Arithmetic arithmetic, Map<String,Object> funcs, Log log) {
        this.uberspect = uberspect == null? Introspector.getUberspect() : uberspect;
        this.arithmetic = arithmetic == null? new JexlArithmetic() : arithmetic;
        if (funcs != null)
            this.functions = funcs;
        if (log == null)
            log = LogFactory.getLog(JexlEngine.class);
        if (log == null)
            throw new NullPointerException("logger can not be null");
        this.LOG = log;
    }
    
    /**
     * Sets whether this engine throws JexlException during evaluation.
     * @param silent true means no JexlException will occur, false allows them
     */
    public void setSilent(boolean silent) {
        this.silent = silent;
    }
    
    /**
     * Checks whether this engine throws JexlException during evaluation.
     */
    public boolean isSilent() {
        return this.silent;
    }
    
    /**
     * Sets the map of function namespaces.
     * <p>
     * It should be defined once not modified afterwards since it might be shared
     * between multiple engines evaluating expressions concurrently.
     * </p>
     * <p>
     * Each entry key is used as a prefix, each entry value used as a bean implementing
     * methods; an expression like 'nsx:method(123)' will thus be solved by looking at
     * a registered bean named 'nsx' that implements method 'method' in that map.
     * If all methods are static, you may use the bean class instead of an instance as value.
     * </p>
     * <p>
     * The key or prefix allows to retrieve the bean that plays the role of the namespace.
     * If the prefix is null, the namespace is the top-level namespace allowing to define
     * top-level user defined functions ( ie: myfunc(...) )
     * </p>
     * <p>
     * Note that you can always use a variable implementing methods & use
     * the 'var.func(...)' syntax if you need more dynamic constructs.
     * </p>
     * @param funcs the map of functions that should not mutate after the call; if null
     * is passed, the empty collection is used.
     */
    public void setFunctions(Map<String, Object> funcs) {
        functions = funcs != null? funcs : Collections.EMPTY_MAP;
    }


    /**
     * Retrieves the map of function namespaces.
     *
     * @return the map passed in setFunctions or the empty map if the
     * original was null.
     */
    public Map<String, Object> getFunctions() {
        return functions;
    }
    
    /**
     * Creates an Expression from a String containing valid
     * JEXL syntax.  This method parses the expression which
     * must contain either a reference or an expression.
     * @param expression A String containing valid JEXL syntax
     * @return An Expression object which can be evaluated with a JexlContext
     * @throws ParseException An exception can be thrown if there is a problem
     *      parsing this expression, or if the expression is neither an
     *      expression or a reference.
     */
    public Expression createExpression(String expression)
        throws ParseException {
        String expr = cleanExpression(expression);

        // Parse the Expression
        SimpleNode tree;
        synchronized (parser) {
            LOG.debug("Parsing expression: " + expr);
            try {
                tree = parser.parse(new StringReader(expr));
            } catch (TokenMgrError tme) {
                throw new ParseException(tme.getMessage());
            } catch (ParseException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        if (tree.jjtGetNumChildren() > 1) {
            LOG.warn("The JEXL Expression created will be a reference"
                + " to the first expression from the supplied script: \""
                + expression + "\" ");
        }

        // Must be a simple reference, expression, statement or if, otherwise
        // throw an exception.
        SimpleNode node = (SimpleNode) tree.jjtGetChild(0);

        return new ExpressionImpl(this, expression, node);
    }

    /**
     * Creates a Script from a String containing valid JEXL syntax.
     * This method parses the script which validates the syntax.
     *
     * @param scriptText A String containing valid JEXL syntax
     * @return A {@link Script} which can be executed with a
     *      {@link JexlContext}.
     * @throws Exception An exception can be thrown if there is a
     *      problem parsing the script.
     */
    public Script createScript(String scriptText) throws Exception {
        String cleanText = cleanExpression(scriptText);
        SimpleNode script;
        // Parse the Expression
        synchronized (parser) {
            LOG.debug("Parsing script: " + cleanText);
            try {
                script = parser.parse(new StringReader(cleanText));
            } catch (TokenMgrError tme) {
                throw new ParseException(tme.getMessage());
            }
        }
        if (script instanceof ASTJexlScript) {
            return new ScriptImpl(this, cleanText, (ASTJexlScript) script);
        } else {
            throw new IllegalStateException("Parsed script is not "
                + "an ASTJexlScript");
        }
    }

    /**
     * Creates a Script from a {@link File} containing valid JEXL syntax.
     * This method parses the script and validates the syntax.
     *
     * @param scriptFile A {@link File} containing valid JEXL syntax.
     *      Must not be null. Must be a readable file.
     * @return A {@link Script} which can be executed with a
     *      {@link JexlContext}.
     * @throws Exception An exception can be thrown if there is a problem
     *      parsing the script.
     */
    public Script createScript(File scriptFile) throws Exception {
        if (scriptFile == null) {
            throw new NullPointerException("scriptFile is null");
        }
        if (!scriptFile.canRead()) {
            throw new IOException("Can't read scriptFile ("
                + scriptFile.getCanonicalPath() + ")");
        }
        BufferedReader reader = new BufferedReader(new FileReader(scriptFile));
        return createScript(readerToString(reader));

    }

    /**
     * Creates a Script from a {@link URL} containing valid JEXL syntax.
     * This method parses the script and validates the syntax.
     *
     * @param scriptUrl A {@link URL} containing valid JEXL syntax.
     *      Must not be null. Must be a readable file.
     * @return A {@link Script} which can be executed with a
     *      {@link JexlContext}.
     * @throws Exception An exception can be thrown if there is a problem
     *      parsing the script.
     */
    public Script createScript(URL scriptUrl) throws Exception {
        if (scriptUrl == null) {
            throw new NullPointerException("scriptUrl is null");
        }
        URLConnection connection = scriptUrl.openConnection();

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(connection.getInputStream()));
        return createScript(readerToString(reader));
    }
    
    /**
     * Creates an interpreter
     */
    protected Interpreter createInterpreter(JexlContext context) {
        return new Interpreter(uberspect, arithmetic, functions, context);
    }
    /**
     * Trims the expression and adds a semi-colon if missing.
     * @param expression to clean
     * @return trimmed expression ending in a semi-colon
     */
    protected String cleanExpression(String expression) {
        String expr = expression.trim();
        if (!expr.endsWith(";")) {
            expr += ";";
        }
        return expr;
    }
    
    /**
     * Read a buffered reader into a StringBuffer and return a String with
     * the contents of the reader.
     * @param reader to be read.
     * @return the contents of the reader as a String.
     * @throws IOException on any error reading the reader.
     */
    protected static String readerToString(BufferedReader reader)
        throws IOException {
        StringBuffer buffer = new StringBuffer();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                buffer.append(line).append('\n');
            }
            return buffer.toString();
        } finally {
            reader.close();
        }

    }
}

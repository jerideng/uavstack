/*-
 * <<
 * UAVStack
 * ==
 * Copyright (C) 2016 - 2017 UAVStack
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */

package com.creditease.uav.feature.runtimenotify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.creditease.agent.helpers.JSONHelper;

/**
 * notify strategy ds
 */
public class NotifyStrategy {

    private static final String[] OPERATORS = { ":=", "!=", ">", "<", "=" };

    private static final Pattern INDEX_PATTERN = Pattern.compile("\\[\\d+\\]");

    //
    private String scope;

    private List<Condition> condtions;

    private String msgTemplate;

    private Map<String, String> action = Collections.emptyMap();

    private List<String> context = Collections.emptyList();

    private List<String> instances = Collections.emptyList();

    private long maxRange = 0;

    public NotifyStrategy() {
    }

    public NotifyStrategy(String scope, List<String> context, Map<String, String> action, List<String> instances,
            String msgTemplate) {
        this.scope = scope;
        if (context != null && context.size() != 0) {
            this.context = context;
        }
        if (action != null && action.size() != 0) {
            this.action = action;
        }
        if (instances != null && instances.size() != 0) {
            this.instances = instances;
        }
        this.msgTemplate = msgTemplate;
    }

    public void setConditions(List<Object> conditions, List<String> relations) {

        int idx = 0; // expression count
        List<Expression> exprs = new ArrayList<>();
        for (Object o : conditions) {

            // condition is simple string: "arg>123"
            if (String.class.isAssignableFrom(o.getClass())) {
                Expression expression = new Expression((String) o);
                expression.setIdx(idx++);
                exprs.add(expression);
            }
            else {
                @SuppressWarnings("unchecked")
                Map<String, Object> cond = (Map<String, Object>) o;
                String expr = (String) cond.get("expr");
                String func = (String) cond.get("func");
                Long range = cond.get("range") == null ? null : Long.valueOf(cond.get("range").toString());
                Float sampling = cond.get("sampling") == null ? null : Float.valueOf(cond.get("sampling").toString());
                Expression expression = new Expression(expr, func, range, sampling);
                expression.setIdx(idx++);
                exprs.add(expression);
            }
        }

        idx = 1; // reuse for condition count, start from 1
        List<Condition> conds = null;
        if (relations == null || relations.isEmpty()) {
            conds = new ArrayList<>(conditions.size());

            for (Expression expr : exprs) {
                conds.add(new Condition(idx++, expr));
            }
        }
        else {
            conds = new ArrayList<>(relations.size());
            for (String relation : relations) {

                Matcher m = INDEX_PATTERN.matcher(relation);
                Set<Expression> set = new HashSet<>();
                while (m.find()) {
                    String idxHolder = m.group();
                    int i = Integer.parseInt(idxHolder.substring(1, idxHolder.length() - 1));
                    if (i >= exprs.size()) { // IndexOutOfBoundsException
                        continue;
                    }
                    set.add(exprs.get(i));
                    relation = relation.replace(idxHolder, "{" + i + "}"); // temp i
                }

                List<Expression> list = new ArrayList<>(set);
                for (int i = 0; i < list.size(); i++) {
                    relation = relation.replace("{" + list.get(i).getIdx() + "}", "[" + i + "]");
                }
                conds.add(new Condition(idx++, list, relation));
            }
        }

        this.condtions = conds;

        /** init max range */
        for (Condition cond : this.condtions) {
            for (Expression expr : cond.expressions) {
                maxRange = Math.max(maxRange, expr.range);
            }
        }

    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static NotifyStrategy parse(String json) {

        Map m = JSONHelper.toObject(json, Map.class);
        String scope = (String) m.get("scope");
        List<String> context = (List<String>) m.get("context");
        List<Object> conditions = (List<Object>) m.get("conditions");
        List<String> relations = (List<String>) m.get("relations");
        Map<String, String> action = (Map<String, String>) m.get("action");
        String msgTemplate = (String) m.get("msgTemplate");
        List<String> instances = (List<String>) m.get("instances");

        NotifyStrategy stra = new NotifyStrategy(scope, context, action, instances, msgTemplate);

        stra.setConditions(conditions, relations);

        return stra;
    }

    public long getMaxRange() {

        return maxRange;
    }

    public String getMsgTemplate() {

        return msgTemplate;
    }

    public void setMsgTemplate(String msgTemplate) {

        this.msgTemplate = msgTemplate;
    }

    public Map<String, String> getAction() {

        return action;
    }

    public void setAction(Map<String, String> action) {

        this.action = action;
    }

    public List<String> getContext() {

        return context;
    }

    public void setContext(List<String> context) {

        this.context = context;
    }

    public String getScope() {

        return scope;
    }

    public void setScope(String scope) {

        this.scope = scope;
    }

    public List<String> getInstances() {

        return instances;
    }

    public void setInstances(List<String> instances) {

        this.instances = instances;
    }

    public List<Condition> getCondtions() {

        return condtions;
    }

    protected static class Expression {

        private int idx;
        private String arg;
        private String operator;
        private String expectedValue;

        private long range = 0;
        private String func;
        private float sampling = 1;

        private Set<String> matchArgExpr = new HashSet<String>();

        public Expression(String exprStr) {
            for (String op : OPERATORS) {
                if (exprStr.contains(op)) {
                    String[] exprs = exprStr.split(op);
                    this.arg = exprs[0].trim();

                    // suport * as a match
                    initMatchArgExpr();

                    this.operator = op;
                    this.expectedValue = exprs[1];
                    break;
                }
            }
        }

        public Expression(String exprStr, String func, Long range, Float sampling) {
            this(exprStr);
            if (range != null && range > 0) {
                this.range = range * 1000; // second to ms
            }
            this.func = func;
            if (sampling != null) {
                this.sampling = sampling;
            }
        }

        private void initMatchArgExpr() {

            if (this.arg.indexOf("*") > -1) {
                String[] tmps = this.arg.split("\\*");
                for (String tmp : tmps) {
                    matchArgExpr.add(tmp);
                }
            }
        }

        public boolean isMatchExpr() {

            return matchArgExpr.size() > 0;
        }

        public Set<String> matchTargetArgs(Set<String> srcArgs) {

            Set<String> targetArgs = new HashSet<String>();

            for (String arg : srcArgs) {

                int matchCount = 0;
                for (String matchField : this.matchArgExpr) {

                    if (arg.indexOf(matchField) > -1) {
                        matchCount++;
                    }
                }

                if (matchCount == this.matchArgExpr.size()) {
                    targetArgs.add(arg);
                }
            }

            return targetArgs;
        }

        public String getArg() {

            return arg;
        }

        public String getOperator() {

            return operator;
        }

        public String getExpectedValue() {

            return expectedValue;
        }

        public long getRange() {

            return range;
        }

        public String getFunc() {

            return func;
        }

        public float getSampling() {

            return sampling;
        }

        public int getIdx() {

            return idx;
        }

        public void setIdx(int idx) {

            this.idx = idx;
        }

    }

    protected class Condition {

        private int index;
        private List<Expression> expressions;
        private String relation;

        public Condition(int index, Expression expr) {
            this.index = index;
            List<Expression> exprs = new ArrayList<>(1);
            exprs.add(expr);
            this.expressions = exprs;
        }

        public Condition(int index, List<Expression> exprs, String relation) {
            this.index = index;
            this.expressions = exprs;
            this.relation = relation;
        }

        public int getIndex() {

            return index;
        }

        public List<Expression> getExpressions() {

            return expressions;
        }

        public String getRelation() {

            return relation;
        }

    }

}

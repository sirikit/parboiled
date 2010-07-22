/*
 * Copyright (C) 2009 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.parboiled.matchers;

import org.jetbrains.annotations.NotNull;
import org.parboiled.Action;
import org.parboiled.ContextAware;
import org.parboiled.MatcherContext;
import org.parboiled.Rule;
import org.parboiled.errors.ActionError;
import org.parboiled.errors.ActionException;
import org.parboiled.errors.GrammarException;
import org.parboiled.transform.BaseAction;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link org.parboiled.matchers.Matcher} that not actually matches input but runs a given parser {@link Action}.
 */
public class ActionMatcher extends AbstractMatcher {

    public final Action action;
    public final List<ContextAware> contextAwares = new ArrayList<ContextAware>();
    public final boolean skipInPredicates;

    public ActionMatcher(@NotNull Action action) {
        this.action = action;

        // Base Actions take care of their context setting need themselves, so we do not need to analyze fields, etc.
        if (action instanceof BaseAction) {
            skipInPredicates = ((BaseAction) action).skipInPredicates();
            return;
        }
        skipInPredicates = false;

        if (action instanceof ContextAware) {
            contextAwares.add((ContextAware) action);
        }
        // in order to make anonymous inner classes and other member classes work seamlessly
        // we collect the synthetic references to the outer parent classes and inform them of
        // the current parsing context if they implement ContextAware
        for (Field field : action.getClass().getDeclaredFields()) {
            if (field.isSynthetic() && ContextAware.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                try {
                    ContextAware contextAware = (ContextAware) field.get(action);
                    if (contextAware != null) contextAwares.add(contextAware);
                } catch (IllegalAccessException e) {
                    // ignore
                } finally {
                    field.setAccessible(false);
                }
            }
        }
    }

    @Override
    public MatcherContext getSubContext(MatcherContext context) {
        MatcherContext subContext = context.getBasicSubContext();
        subContext.setMatcher(this);
        // if the subcontext contains match data we use the existing subcontext without reinitializing
        // this way we can access the match data of the previous match from this action
        return subContext.getCurrentIndex() > 0 ? subContext : context.getSubContext(this);
    }

    @SuppressWarnings({"unchecked"})
    public <V> boolean match(@NotNull MatcherContext<V> context) {
        if (skipInPredicates && context.inPredicate()) return true;

        // actions need to run in the parent context
        MatcherContext parentContext = context.getParent();
        if (!contextAwares.isEmpty()) {
            for (ContextAware contextAware : contextAwares) {
                contextAware.setContext(parentContext);
            }
        }

        try {
            if (!action.run(parentContext)) return false;

            // since we initialize the actions own context only partially in getSubContext(MatcherContext)
            // (in order to be able to still access the previous subcontexts fields in action expressions)
            // we need to make sure to not accidentally advance the current index of our parent with some old
            // index from a previous subcontext, so we explicitly set the marker here
            context.setCurrentIndex(parentContext.getCurrentIndex());
            return true;
        } catch (ActionException e) {
            context.getParseErrors().add(new ActionError(context.getInputBuffer(), context.getCurrentIndex(),
                    e.getMessage(), context.getPath(), e));
            return false;
        }
    }

    @Override
    public Rule suppressNode() {
        throw new GrammarException("Actions cannot be marked with @SuppressNode or @SuppressSubnodes");
    }

    public <R> R accept(@NotNull MatcherVisitor<R> visitor) {
        return visitor.visit(this);
    }

}
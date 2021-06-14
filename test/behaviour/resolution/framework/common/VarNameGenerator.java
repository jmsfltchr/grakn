/*
 * Copyright (C) 2021 Vaticle
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.vaticle.typedb.core.test.behaviour.resolution.framework.common;

import com.vaticle.typedb.core.common.exception.TypeDBException;
import com.vaticle.typeql.lang.TypeQL;
import com.vaticle.typeql.lang.pattern.constraint.ThingConstraint;
import com.vaticle.typeql.lang.pattern.variable.BoundVariable;
import com.vaticle.typeql.lang.pattern.variable.ThingVariable;
import com.vaticle.typeql.lang.pattern.variable.UnboundVariable;

import java.util.HashMap;
import java.util.List;

import static com.vaticle.typedb.core.common.exception.ErrorMessage.Internal.ILLEGAL_STATE;
import static com.vaticle.typedb.core.test.behaviour.resolution.framework.common.VarNameGenerator.VarPrefix.*;


public class VarNameGenerator {

    private final HashMap<String, Integer> nextVarIndex = new HashMap<>();

    enum VarPrefix {
        X("x");

        private final String name;

        VarPrefix(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Creates a new variable by incrementing a value
     *
     * @param prefix The prefix to use to uniquely identify a set of incremented variables, e.g. `x` will give
     *               `x0`, `x1`, `x2`...
     * @return prefix followed by an auto-incremented integer, as a string
     */
    public String getNextVarName(String prefix) {
        nextVarIndex.putIfAbsent(prefix, 0);
        int currentIndex = nextVarIndex.get(prefix);
        String nextVar = prefix + currentIndex;
        nextVarIndex.put(prefix, currentIndex + 1);
        return nextVar;
    }

    public BoundVariable makeAnonVarsExplicit(BoundVariable variable) {
        if (variable.isThing()) return makeAnonVarsExplicit(variable.asThing());
        else throw TypeDBException.of(ILLEGAL_STATE); // TODO: Check this is illegal
    }

    private ThingVariable<?> makeAnonVarsExplicit(ThingVariable<?> variable) {
        if (variable.isNamed()) {
            return variable;
        } else {
            List<ThingConstraint> constraints = variable.constraints();
            // TODO: Generate some new name
            ThingVariable<?> vb = null;
            UnboundVariable v = TypeQL.var(getNextVarName(X.toString()));
            // TODO: This needs to return a bound variable. The variables must have one or more constraints, but how can we know this?
            for (ThingConstraint constraint : constraints) {
                if (constraint.isIsa()) vb = v.constrain(constraint.asIsa());
                else if (constraint.isHas()) vb = v.constrain(constraint.asHas());
                else if (constraint.isIID()) vb = v.constrain(constraint.asIID());
                else if (constraint.isRelation()) vb = v.constrain(constraint.asRelation());
                else if (constraint.isValue()) vb = v.constrain(constraint.asValue());
                else throw TypeDBException.of(ILLEGAL_STATE);
            }
            if (vb == null) throw TypeDBException.of(ILLEGAL_STATE); // TODO: Is there a nicer way to enforce a constraint is found?
            return vb;
        }
    }
}

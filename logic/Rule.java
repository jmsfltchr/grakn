/*
 * Copyright (C) 2021 Grakn Labs
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

package grakn.core.logic;

import grakn.core.common.exception.GraknException;
import grakn.core.common.iterator.Iterators;
import grakn.core.common.iterator.ResourceIterator;
import grakn.core.common.parameters.Label;
import grakn.core.concept.Concept;
import grakn.core.concept.ConceptManager;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.concept.thing.Attribute;
import grakn.core.concept.thing.Thing;
import grakn.core.concept.type.AttributeType;
import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.RoleType;
import grakn.core.graph.GraphManager;
import grakn.core.graph.structure.RuleStructure;
import grakn.core.logic.resolvable.Concludable;
import grakn.core.pattern.Conjunction;
import grakn.core.pattern.constraint.thing.HasConstraint;
import grakn.core.pattern.constraint.thing.IsaConstraint;
import grakn.core.pattern.constraint.thing.RelationConstraint;
import grakn.core.pattern.constraint.thing.ThingConstraint;
import grakn.core.pattern.constraint.thing.ValueConstraint;
import grakn.core.pattern.variable.SystemReference;
import grakn.core.pattern.variable.TypeVariable;
import grakn.core.pattern.variable.Variable;
import grakn.core.pattern.variable.VariableRegistry;
import grakn.core.traversal.Traversal;
import grakn.core.traversal.TraversalEngine;
import grakn.core.traversal.common.Identifier;
import graql.lang.pattern.Pattern;
import graql.lang.pattern.variable.Reference;
import graql.lang.pattern.variable.ThingVariable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static grakn.common.collection.Collections.list;
import static grakn.common.collection.Collections.set;
import static grakn.common.util.Objects.className;
import static grakn.core.common.exception.ErrorMessage.Internal.ILLEGAL_STATE;
import static grakn.core.common.exception.ErrorMessage.Pattern.INVALID_CASTING;
import static grakn.core.logic.LogicManager.validateRuleStructureLabels;


public class Rule {

    private final LogicManager logicManager;
    private final RuleStructure structure;
    private final Conjunction when;
    private final Conjunction then;
    private final Conclusion conclusion;
    private final Set<Concludable> requiredWhenConcludables;

    private Rule(LogicManager logicManager, RuleStructure structure) {
        this.logicManager = logicManager;
        this.structure = structure;
        // TODO enable when we have type hinting
//        this.when = logicManager.typeHinter().computeHintsExhaustive(whenPattern(structure.when()));
//        this.then = logicManager.typeHinter().computeHintsExhaustive(thenPattern(structure.then()));
        this.when = whenPattern(structure.when());
        this.then = thenPattern(structure.then());
        pruneThenResolvedTypes();
        this.conclusion = Conclusion.create(this.then);
        this.requiredWhenConcludables = Concludable.create(this.when);
    }

    private Rule(GraphManager graphMgr, ConceptManager conceptMgr, LogicManager logicManager, String label,
                 graql.lang.pattern.Conjunction<? extends Pattern> when, ThingVariable<?> then) {
        this.logicManager = logicManager;
        this.structure = graphMgr.schema().create(label, when, then);
        validateRuleStructureLabels(conceptMgr, this.structure);
        // TODO enable when we have type hinting
//        this.when = logicManager.typeHinter().computeHintsExhaustive(whenPattern(structure.when()));
//        this.then = logicManager.typeHinter().computeHintsExhaustive(thenPattern(structure.then()));
        this.when = whenPattern(structure.when());
        this.then = thenPattern(structure.then());
        validateSatisfiable();
        pruneThenResolvedTypes();

        this.conclusion = Conclusion.create(this.then);
        this.requiredWhenConcludables = Concludable.create(this.when);
        validateCycles();
    }

    public static Rule of(LogicManager logicManager, RuleStructure structure) {
        return new Rule(logicManager, structure);
    }

    public static Rule of(GraphManager graphMgr, ConceptManager conceptMgr, LogicManager logicManager, String label,
                          graql.lang.pattern.Conjunction<? extends Pattern> when, ThingVariable<?> then) {
        return new Rule(graphMgr, conceptMgr, logicManager, label, when, then);
    }

    public Set<Concludable> whenConcludables() {
        return requiredWhenConcludables;
    }

    public Conclusion conclusion() {
        return conclusion;
    }

    public Map<Identifier, Concept> putConclusion(ConceptMap whenConcepts, TraversalEngine traversalEng, ConceptManager conceptMgr) {
        return conclusion.putConclusion(whenConcepts, traversalEng, conceptMgr);
    }

    public Conjunction when() {
        return when;
    }

    public String getLabel() {
        return structure.label();
    }

    public void setLabel(String label) {
        structure.label(label);
    }

    public boolean isDeleted() {
        return structure.isDeleted();
    }

    public void delete() {
        structure.delete();
    }

    public ThingVariable<?> getThenPreNormalised() {
        return structure.then();
    }

    public graql.lang.pattern.Conjunction<? extends Pattern> getWhenPreNormalised() {
        return structure.when();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (object == null || getClass() != object.getClass()) return false;

        final Rule that = (Rule) object;
        return this.structure.equals(that.structure);
    }

    @Override
    public final int hashCode() {
        return structure.hashCode(); // does not need caching
    }

    void validateSatisfiable() {
        // TODO: check that the rule has a set of satisfiable types. This includes strictly for the `when` of the rule
        // TODO instead of the collapsed type hints on the `isa` and `sub` constraints
        // TODO: and also checking that each combinations of types from the `when` is a valid insertable answer in the `then`
        // TODO: we may want to use the stream of combinations of types directly from type inferences, without collapsing them
    }

    void validateCycles() {
        // TODO implement this when we have negation
        // TODO detect negated cycles in the rule graph
        // TODO use the new rule as a starting point
        // throw GraknException.of(ErrorMessage.RuleWrite.RULES_IN_NEGATED_CYCLE_NOT_STRATIFIABLE.message(rule));
    }

    /**
     * Remove type hints in the `then` pattern that are not valid in the `when` pattern
     */
    private void pruneThenResolvedTypes() {
        // TODO name is inconsistent with elsewhere
        then.variables().stream().filter(variable -> variable.id().isNamedReference())
                .forEach(thenVar ->
                                 when.variables().stream()
                                         .filter(whenVar -> whenVar.id().equals(thenVar.id()))
                                         .filter(whenVar -> !(whenVar.isSatisfiable() && whenVar.resolvedTypes().isEmpty()))
                                         .findFirst().ifPresent(whenVar -> {
                                     if (thenVar.resolvedTypes().isEmpty() && thenVar.isSatisfiable()) {
                                         thenVar.addResolvedTypes(whenVar.resolvedTypes());
                                     } else thenVar.retainResolvedTypes(whenVar.resolvedTypes());
                                     if (thenVar.resolvedTypes().isEmpty()) thenVar.setSatisfiable(false);
                                 })
                );
    }

    private Conjunction whenPattern(graql.lang.pattern.Conjunction<? extends Pattern> conjunction) {
        return logicManager.typeResolver().resolveLabels(
                Conjunction.create(conjunction.normalise().patterns().get(0)));
    }

    private Conjunction thenPattern(ThingVariable<?> thenVariable) {
        return logicManager.typeResolver().resolveLabels(
                new Conjunction(VariableRegistry.createFromThings(list(thenVariable)).constraints(), set()));
    }

    public static abstract class Conclusion {

        public static Conclusion create(Conjunction then) {
            Optional<Relation> r = Relation.of(then);
            if ((r).isPresent()) return r.get();
            Optional<Has.Explicit> e = Has.Explicit.of(then);
            if (e.isPresent()) return e.get();
            Optional<Has.Variable> v = Has.Variable.of(then);
            if (v.isPresent()) return v.get();
            throw GraknException.of(ILLEGAL_STATE);
        }

        public abstract Map<Identifier, Concept> putConclusion(ConceptMap whenConcepts, TraversalEngine traversalEng, ConceptManager conceptMgr);

        public boolean isRelation() {
            return false;
        }

        public boolean isHas() {
            return false;
        }

        public boolean isIsa() {
            return false;
        }

        public boolean isValue() {
            return false;
        }

        public boolean isExplicitHas() {
            return false;
        }

        public boolean isVariableHas() {
            return false;
        }

        public Relation asRelation() {
            throw GraknException.of(INVALID_CASTING, className(this.getClass()), className(Relation.class));
        }

        public Has asHas() {
            throw GraknException.of(INVALID_CASTING, className(this.getClass()), className(Has.class));
        }

        public Isa asIsa() {
            throw GraknException.of(INVALID_CASTING, className(this.getClass()), className(Isa.class));
        }

        public Value asValue() {
            throw GraknException.of(INVALID_CASTING, className(this.getClass()), className(Value.class));
        }

        public Has.Variable asVariableHas() {
            throw GraknException.of(INVALID_CASTING, className(this.getClass()), className(Has.Variable.class));
        }

        public Has.Explicit asExplicitHas() {
            throw GraknException.of(INVALID_CASTING, className(this.getClass()), className(Has.Explicit.class));
        }

        public interface Isa {
            IsaConstraint isa();
        }

        public interface Value {
            ValueConstraint<?> value();
        }

        public static class Relation extends Conclusion implements Isa {

            private final RelationConstraint relation;
            private final IsaConstraint isa;

            public static Optional<Relation> of(Conjunction conjunction) {
                return Iterators.iterate(conjunction.variables()).filter(Variable::isThing).map(Variable::asThing)
                        .flatMap(variable -> Iterators.iterate(variable.constraints())
                                .filter(ThingConstraint::isRelation)
                                .map(constraint -> {
                                    assert constraint.owner().isa().isPresent();
                                    return new Relation(constraint.asRelation(), variable.isa().get());
                                })).first();
            }

            public Relation(RelationConstraint relation, IsaConstraint isa) {
                this.relation = relation;
                this.isa = isa;
            }

            @Override
            public Map<Identifier, Concept> putConclusion(ConceptMap whenConcepts, TraversalEngine traversalEng, ConceptManager conceptMgr) {
                Identifier relationTypeIdentifier = isa().type().id();
                RelationType relationType = relationType(whenConcepts, conceptMgr);
                Set<RolePlayer> players = new HashSet<>();
                relation().players().forEach(rp -> players.add(new RolePlayer(rp, relationType, whenConcepts)));
                Optional<grakn.core.concept.thing.Relation> relationInstance = matchRelation(relationType, players, traversalEng, conceptMgr);

                Map<Identifier, Concept> thenConcepts = new HashMap<>();
                if (relationInstance.isPresent()) {
                    thenConcepts.put(relationTypeIdentifier, relationInstance.get());
                } else {
                    grakn.core.concept.thing.Relation relation = insertRelation(relationType, players);
                    thenConcepts.put(relationTypeIdentifier, relation);
                }
                players.forEach(rp -> {
                    thenConcepts.putIfAbsent(rp.roleTypeIdentifier, rp.roleType);
                    thenConcepts.putIfAbsent(rp.playerIdentifier, rp.player);
                });
                return thenConcepts;
            }

            public RelationConstraint relation() {
                return relation;
            }

            @Override
            public IsaConstraint isa() {
                return isa;
            }

            @Override
            public boolean isIsa() {
                return true;
            }

            @Override
            public boolean isRelation() {
                return true;
            }

            public Isa asIsa() {
                return this;
            }

            @Override
            public Relation asRelation() {
                return this;
            }


            private grakn.core.concept.thing.Relation insertRelation(RelationType relationType, Set<RolePlayer> players) {
                grakn.core.concept.thing.Relation relation = relationType.create(true);
                players.forEach(rp -> relation.addPlayer(rp.roleType, rp.player, true));
                return relation;
            }

            private Optional<grakn.core.concept.thing.Relation> matchRelation(RelationType relationType, Set<RolePlayer> players,
                                                                              TraversalEngine traversalEng, ConceptManager conceptMgr) {
                Traversal traversal = new Traversal();
                SystemReference relationRef = SystemReference.of(0);
                Identifier.Variable relationId = Identifier.Variable.of(relationRef);
                traversal.isa(relationId, Identifier.Variable.label(relationType.getLabel().name()), false);
                players.forEach(rp -> {
                    // note: NON-transitive role player types - we require an exact role being played
                    traversal.rolePlayer(relationId, rp.playerIdentifier, set(rp.roleType.getLabel()), rp.repetition);
                    traversal.iid(rp.playerIdentifier, rp.player.getIID());
                });
                ResourceIterator<ConceptMap> iterator = traversalEng.iterator(traversal).map(conceptMgr::conceptMap);
                if (iterator.hasNext()) return Optional.of(iterator.next().get(relationRef).asRelation());
                else return Optional.empty();
            }

            private RelationType relationType(ConceptMap whenConcepts, ConceptManager conceptMgr) {
                if (isa().type().reference().isName()) {
                    Reference.Name typeReference = isa().type().reference().asName();
                    assert whenConcepts.contains(typeReference) && whenConcepts.get(typeReference).isRelationType();
                    return whenConcepts.get(typeReference).asRelationType();
                } else {
                    assert isa().type().reference().isLabel();
                    return conceptMgr.getRelationType(isa().type().label().get().label());
                }
            }

            private static class RolePlayer {
                private final Identifier roleTypeIdentifier;
                private final RoleType roleType;
                private final Identifier.Variable playerIdentifier;
                private final Thing player;
                private final int repetition;

                public RolePlayer(RelationConstraint.RolePlayer rp, RelationType scope, ConceptMap whenConcepts) {
                    assert rp.roleType().isPresent();
                    roleTypeIdentifier = rp.roleType().get().id();
                    if (rp.roleType().get().reference().isName()) {
                        roleType = whenConcepts.get(rp.roleType().get().reference().asName()).asRoleType();
                    } else {
                        assert rp.roleType().get().reference().isLabel();
                        roleType = scope.getRelates(rp.roleType().get().label().get().properLabel().name());
                    }
                    assert whenConcepts.contains(rp.player().reference().asName());
                    playerIdentifier = Identifier.Variable.of(rp.player().reference().asName());
                    player = whenConcepts.get(rp.player().reference().asName()).asThing();
                    repetition = rp.repetition();
                }
            }

        }

        public static abstract class Has extends Conclusion {

            private final HasConstraint has;

            Has(HasConstraint has) {
                this.has = has;
            }

            public HasConstraint has() {
                return has;
            }

            @Override
            public Has asHas() {
                return this;
            }

            @Override
            public boolean isHas() {
                return true;
            }

            public static class Explicit extends Has implements Isa, Value {

                private final IsaConstraint isa;
                private final ValueConstraint<?> value;

                private Explicit(HasConstraint has, IsaConstraint isa, ValueConstraint<?> value) {
                    super(has);
                    this.isa = isa;
                    this.value = value;
                }

                public static Optional<Explicit> of(Conjunction conjunction) {
                    return Iterators.iterate(conjunction.variables()).filter(grakn.core.pattern.variable.Variable::isThing)
                            .map(grakn.core.pattern.variable.Variable::asThing)
                            .flatMap(variable -> Iterators.iterate(variable.constraints()).filter(ThingConstraint::isHas)
                                    .filter(constraint -> constraint.asHas().attribute().id().reference().isAnonymous())
                                    .map(constraint -> {
                                        assert constraint.asHas().attribute().isa().isPresent();
                                        assert constraint.asHas().attribute().isa().get().type().label().isPresent();
                                        assert constraint.asHas().attribute().value().size() == 1;
                                        return new Has.Explicit(constraint.asHas(), constraint.asHas().attribute().isa().get(),
                                                                constraint.asHas().attribute().value().iterator().next());
                                    })).first();
                }

                @Override
                public Map<Identifier, Concept> putConclusion(ConceptMap whenConcepts, TraversalEngine traversalEng, ConceptManager conceptMgr) {
                    Identifier.Variable ownerId = has().owner().id();
                    assert whenConcepts.contains(ownerId.reference().asName()) && whenConcepts.get(ownerId.reference().asName()).isThing();
                    Thing owner = whenConcepts.get(ownerId.reference().asName()).asThing();
                    Map<Identifier, Concept> thenConcepts = new HashMap<>();
                    Attribute attribute = getOrCreateAttribute(conceptMgr);
                    owner.setHas(attribute, true);
                    TypeVariable declaredType = has().attribute().isa().get().type();
                    Identifier declaredTypeIdentifier = declaredType.id();
                    AttributeType attrType = conceptMgr.getAttributeType(declaredType.label().get().properLabel().name());
                    assert attrType.equals(attribute.getType());
                    thenConcepts.put(declaredTypeIdentifier, attrType);
                    thenConcepts.put(has().attribute().id(), attribute);
                    thenConcepts.put(has().owner().id(), owner);
                    return thenConcepts;
                }

                @Override
                public boolean isExplicitHas() {
                    return true;
                }

                @Override
                public Has.Explicit asExplicitHas() {
                    return this;
                }

                @Override
                public IsaConstraint isa() {
                    return isa;
                }

                @Override
                public boolean isIsa() {
                    return true;
                }

                @Override
                public Isa asIsa() {
                    return this;
                }

                @Override
                public ValueConstraint<?> value() {
                    return value;
                }


                private Attribute getOrCreateAttribute(ConceptManager conceptMgr) {
                    assert has().attribute().isa().isPresent()
                            && has().attribute().isa().get().type().label().isPresent()
                            && has().attribute().value().size() == 1
                            && has().attribute().value().iterator().next().isValueIdentity();
                    Label attributeTypeLabel = has().attribute().isa().get().type().label().get().properLabel();
                    AttributeType attributeType = conceptMgr.getAttributeType(attributeTypeLabel.name());
                    assert attributeType != null;
                    ValueConstraint<?> value = has().attribute().value().iterator().next();
                    if (value.isBoolean()) return attributeType.asBoolean().put(value.asBoolean().value(), true);
                    else if (value.isDateTime())
                        return attributeType.asDateTime().put(value.asDateTime().value(), true);
                    else if (value.isDouble()) return attributeType.asDouble().put(value.asDouble().value(), true);
                    else if (value.isLong()) return attributeType.asLong().put(value.asLong().value(), true);
                    else if (value.isString()) return attributeType.asString().put(value.asString().value(), true);
                    else throw GraknException.of(ILLEGAL_STATE);
                }

            }

            public static class Variable extends Has {

                private Variable(HasConstraint hasConstraint) {
                    super(hasConstraint);
                }

                public static Optional<Variable> of(Conjunction conjunction) {
                    return Iterators.iterate(conjunction.variables()).filter(grakn.core.pattern.variable.Variable::isThing)
                            .map(grakn.core.pattern.variable.Variable::asThing)
                            .flatMap(variable -> Iterators.iterate(variable.constraints()).filter(ThingConstraint::isHas)
                                    .filter(constraint -> constraint.asHas().attribute().id().isNamedReference())
                                    .map(constraint -> {
                                        assert !constraint.asHas().attribute().isa().isPresent();
                                        assert constraint.asHas().attribute().value().size() == 0;
                                        return new Has.Variable(constraint.asHas());
                                    })).first();
                }

                @Override
                public Map<Identifier, Concept> putConclusion(ConceptMap whenConcepts, TraversalEngine traversalEng, ConceptManager conceptMgr) {
                    Identifier.Variable ownerId = has().owner().id();
                    assert whenConcepts.contains(ownerId.reference().asName())
                            && whenConcepts.get(ownerId.reference().asName()).isThing();
                    Thing owner = whenConcepts.get(ownerId.reference().asName()).asThing();
                    Map<Identifier, Concept> thenConcepts = new HashMap<>();
                    assert whenConcepts.contains(has().attribute().reference().asName())
                            && whenConcepts.get(has().attribute().reference().asName()).isAttribute();
                    Attribute attribute = whenConcepts.get(has().attribute().reference().asName()).asAttribute();
                    owner.setHas(attribute, true);
                    thenConcepts.put(has().attribute().id(), attribute);
                    thenConcepts.put(has().owner().id(), owner);
                    return thenConcepts;
                }

                @Override
                public boolean isVariableHas() {
                    return true;
                }

                @Override
                public Variable asVariableHas() {
                    return this;
                }
            }

        }
    }
}

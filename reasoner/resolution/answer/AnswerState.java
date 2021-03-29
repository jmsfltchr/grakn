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
 */

package grakn.core.reasoner.resolution.answer;

import grakn.core.common.exception.GraknException;
import grakn.core.concept.Concept;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.concurrent.actor.Actor;
import grakn.core.logic.Rule;
import grakn.core.logic.resolvable.Unifier;
import grakn.core.pattern.Conjunction;
import grakn.core.reasoner.resolution.framework.Resolver;
import grakn.core.traversal.common.Identifier;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static grakn.common.util.Objects.className;
import static grakn.core.common.exception.ErrorMessage.Internal.ILLEGAL_CAST;
import static grakn.core.common.exception.ErrorMessage.Pattern.INVALID_CASTING;

public interface AnswerState {

    interface Explainable {

        boolean explainable();

    }

    ConceptMap conceptMap();

    boolean requiresReiteration();

    void requiresReiteration(boolean requiresReiteration);

    Actor.Driver<? extends Resolver<?>> root();

    default boolean isTop() { return false; }

    default Top asTop() { throw GraknException.of(INVALID_CASTING, className(this.getClass()), className(Top.class)); }

    default boolean isPartial() { return false; }

    default Partial<?> asPartial() { throw GraknException.of(INVALID_CASTING, className(this.getClass()), className(Partial.class)); }

    interface Top extends AnswerState {

        default boolean isTop() { return true; }

        default Top asTop() { return this; }

        default boolean isMatch() { return false; }

        default Match asMatch() { throw GraknException.of(ILLEGAL_CAST, this.getClass(), Match.class); }

        default boolean isExplain() { return false; }

        default Explain asExplain() { throw GraknException.of(ILLEGAL_CAST, this.getClass(), Explain.class); }

        interface Match extends Top, Explainable {

            Set<Identifier.Variable.Name> getFilter();

            @Override
            default boolean isMatch() {
                return true;
            }

            @Override
            default Match asMatch() {
                return this;
            }

            default boolean isFinished() { return false; }

            default Finished asFinished() { throw GraknException.of(ILLEGAL_CAST, this.getClass(), Finished.class); }

            interface Initial extends Match {

                Partial.Compound.Root.Root.Match toDownstream();

                Finished finish(ConceptMap conceptMap, boolean requiresReiteration);

            }

            interface Finished extends Match {

                @Override
                default boolean isFinished() { return true; }

                @Override
                default Finished asFinished() { return this; }

            }

        }

        interface Explain extends Top {

            @Override
            default boolean isExplain() { return true; }

            @Override
            default Explain asExplain() { return this; }

            default boolean isFinished() { return false; }

            default Finished asFinished() { throw GraknException.of(ILLEGAL_CAST, this.getClass(), Finished.class); }

            interface Initial extends Explain {

                Partial.Compound.Root.Explain toDownstream();

                Finished finish(ConceptMap conceptMap, boolean requiresReiteration, Explanation explanation);

            }

            interface Finished extends Explain {

                Explanation explanation();

                @Override
                default boolean isFinished() { return true; }

                @Override
                default Finished asFinished() { return this; }

            }
        }
    }

    interface Partial<PARENT extends AnswerState> extends AnswerState {

        PARENT parent();

        @Override
        default boolean isPartial() { return true; }

        @Override
        default Partial<?> asPartial() { return this; }

        default boolean isCompound() { return false; }

        default boolean isConcludable() { return false; }

        default boolean isConclusion() { return false; }

        default boolean isRetrievable() { return false; }

        default Compound<?, ?> asCompound() {
            throw GraknException.of(INVALID_CASTING, className(this.getClass()), className(Compound.class));
        }

        default Concludable<?> asConcludable() {
            throw GraknException.of(INVALID_CASTING, className(this.getClass()), className(Concludable.class));
        }

        default Conclusion<?, ?> asConclusion() {
            throw GraknException.of(INVALID_CASTING, className(this.getClass()), className(Conclusion.class));
        }

        default Retrievable<?> asRetrievable() {
            throw GraknException.of(INVALID_CASTING, className(this.getClass()), className(Retrievable.class));
        }

        interface Compound<SLF extends Compound<SLF, PRNT>, PRNT extends AnswerState> extends Partial<PRNT> {

            SLF with(ConceptMap extension, boolean requiresReiteration);

            Concludable<SLF> toDownstream(Mapping mapping, grakn.core.logic.resolvable.Concludable concludable);

            Nestable filterToNestable(Set<Identifier.Variable.Retrievable> filter);

            Retrievable<SLF> filterToRetrievable(Set<Identifier.Variable.Retrievable> filter);

            @Override
            default boolean isCompound() { return true; }

            @Override
            default Compound<?, PRNT> asCompound() { return this; }

            default boolean isRoot() { return false; }

            default Root<?, ?> asRoot() { throw GraknException.of(ILLEGAL_CAST, this.getClass(), Root.class); }

            default boolean isCondition() { return false; }

            default Condition<?, ?> asCondition() { throw GraknException.of(ILLEGAL_CAST, this.getClass(), Condition.class); }

            default boolean isNestable() { return false; }

            default Nestable asNestable() { throw GraknException.of(ILLEGAL_CAST, this.getClass(), Nestable.class); }

            default boolean isExplain() { return false; }

            default boolean isMatch() { return false; }

            interface Nestable extends Compound<Nestable, Compound<?, ?>> {

                Set<Identifier.Variable.Retrievable> filter();

                Partial.Compound<?, ?> toUpstream();

                @Override
                Concludable.Match<Nestable> toDownstream(Mapping mapping, grakn.core.logic.resolvable.Concludable concludable);

                @Override
                default boolean isNestable() { return true; }

                @Override
                default Nestable asNestable() { return this; }

            }

            interface Root<S extends Root<S, P>, P extends AnswerState> extends Compound<S, P> {

                @Override
                default boolean isRoot() { return true; }

                @Override
                default Root<?, P> asRoot() { return this; }

                default Match asMatch() { throw GraknException.of(ILLEGAL_CAST, this.getClass(), Match.class); }

                default Explain asExplain() { throw GraknException.of(ILLEGAL_CAST, this.getClass(), Explain.class); }

                interface Match extends Root<Match, Top.Match.Initial>, Explainable {

                    @Override
                    Concludable.Match<Match> toDownstream(Mapping mapping, grakn.core.logic.resolvable.Concludable concludable);

                    Top.Match.Finished toFinishedTop(Conjunction conjunctionAnswered);

                    @Override
                    default boolean isMatch() { return true; }

                    @Override
                    default Match asMatch() { return this; }

                }

                interface Explain extends Root<Explain, Top.Explain.Initial> {

                    Explain with(ConceptMap extension, boolean requiresReiteration, Explanation explanation);

                    @Override
                    Concludable.Explain toDownstream(Mapping mapping,grakn.core.logic.resolvable.Concludable concludable);

                    Top.Explain.Finished toFinishedTop();

                    @Override
                    default boolean isExplain() { return true; }

                    @Override
                    default Explain asExplain() { return this; }

                }
            }

            interface Condition<S extends Condition<S, P>, P extends Conclusion<P, ?>> extends Compound<S, P> {

                // merge point where Match and Explain all become Match states
                Concludable.Match<S> toDownstream(Mapping mapping, grakn.core.logic.resolvable.Concludable concludable);

                Conclusion<?, ?> toUpstream();

                @Override
                default boolean isCondition() { return true; }

                @Override
                default Condition<?, P> asCondition() { return this; }

                default Match asMatch() { throw GraknException.of(ILLEGAL_CAST, this.getClass(), Root.Match.class); }

                default Explain asExplain() { throw GraknException.of(ILLEGAL_CAST, this.getClass(), Root.Explain.class); }

                interface Match extends Condition<Match, Conclusion.Match> {

                    Conclusion.Match toUpstream();

                    @Override
                    default boolean isMatch() { return true; }

                    @Override
                    default Match asMatch() { return this; }

                }

                interface Explain extends Condition<Explain, Conclusion.Explain> {

                    Conclusion.Explain toUpstream();

                    @Override
                    default boolean isExplain() { return true; }

                    @Override
                    default Explain asExplain() { return this; }

                }

            }

        }

        interface Concludable<PRNT extends Compound<PRNT, ?>> extends Partial<PRNT> {

            Mapping mapping();

            PRNT toUpstreamInferred();

            Optional<? extends Conclusion<?, ?>> toDownstream(Unifier unifier, Rule rule);

            @Override
            default boolean isConcludable() { return true; }

            @Override
            default Concludable<?> asConcludable() { return this; }

            default boolean isMatch() { return false; }

            default Match<?> asMatch() { throw GraknException.of(ILLEGAL_CAST, this.getClass(), Match.class); }

            default boolean isExplain() { return false; }

            default Explain asExplain() { throw GraknException.of(ILLEGAL_CAST, this.getClass(), Explain.class); }

            interface Match<P extends Compound<P, ?>> extends Concludable<P>, Explainable {

                @Override
                Optional<Conclusion.Match> toDownstream(Unifier unifier, Rule rule);

                Match<P> with(ConceptMap extension, boolean requiresReiteration);

                P toUpstreamLookup(ConceptMap additionalConcepts, boolean isInferredConclusion);

                @Override
                default boolean isMatch() { return true; }

                @Override
                default Match<?> asMatch() { return this; }

            }

            interface Explain extends Concludable<Compound.Root.Explain> {

                @Override
                Optional<Conclusion.Explain> toDownstream(Unifier unifier, Rule rule);

                Explain with(ConceptMap extension, boolean requiresReiteration, Rule rule, ConceptMap conclusionAnswer,
                             Unifier unifier, ConceptMap conditionAnswer);

                @Override
                default boolean isExplain() {
                    return true;
                }

                @Override
                default Explain asExplain() { return this; }
            }

        }

        interface Conclusion<SLF extends Conclusion<SLF, PRNT>, PRNT extends Concludable<?>> extends Partial<PRNT> {

            Rule rule();

            Unifier unifier();

            Unifier.Requirements.Instance instanceRequirements();

            Optional<? extends PRNT> aggregateToUpstream(Map<Identifier.Variable, Concept> concepts);

            SLF extend(ConceptMap ans);

            Compound<?, SLF> toDownstream(Set<Identifier.Variable.Retrievable> filter);

            @Override
            default boolean isConclusion() { return true; }

            @Override
            default Conclusion<?, ?> asConclusion() { return this; }

            interface Match extends Conclusion<Match, Concludable.Match<?>>, Explainable {

                @Override
                Optional<Concludable.Match<?>> aggregateToUpstream(Map<Identifier.Variable, Concept> concepts);

                Match with(ConceptMap extension, boolean requiresReiteration);

                @Override
                Compound.Root.Condition.Match toDownstream(Set<Identifier.Variable.Retrievable> filter);

            }

            interface Explain extends Conclusion<Explain, Concludable.Explain> {

                ConceptMap conditionAnswer();

                Explain with(ConceptMap conditionAnswer, boolean requiresReiteration);

                @Override
                Optional<Concludable.Explain> aggregateToUpstream(Map<Identifier.Variable, Concept> concepts);

                @Override
                Compound.Condition.Explain toDownstream(Set<Identifier.Variable.Retrievable> filter);

            }

        }

        interface Retrievable<P extends Compound<P, ?>> extends Partial<P> {

            P aggregateToUpstream(ConceptMap concepts);

            @Override
            default boolean isRetrievable() { return true; }

            @Override
            default Retrievable<?> asRetrievable() { return this; }

        }

    }

}


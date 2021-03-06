/*
 * Copyright 2017 California Institute of Technology ("Caltech").
 * U.S. Government sponsorship acknowledged.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * License Terms
 */

package gov.nasa.jpl.imce.oml.converters.internal

import java.lang.{IllegalArgumentException, System}
import java.util.UUID

import gov.nasa.jpl.imce.oml.converters.ConversionCommand
import gov.nasa.jpl.imce.oml.resolver.Filterable._
import gov.nasa.jpl.imce.oml.resolver.api
import gov.nasa.jpl.imce.oml.resolver.Extent2Tables.toUUIDString
import gov.nasa.jpl.imce.oml.{resolver, tables}
import gov.nasa.jpl.omf.scala.binding.owlapi
import gov.nasa.jpl.omf.scala.binding.owlapi.descriptions.ImmutableDescriptionBox
import gov.nasa.jpl.omf.scala.binding.owlapi.types.terminologies.ImmutableTerminologyBox
import gov.nasa.jpl.omf.scala.binding.owlapi.types.{terminologies => owlapiterminologies}
import gov.nasa.jpl.omf.scala.binding.owlapi.{OWLAPIOMFGraphStore, OntologyMapping, descriptions => owlapidescriptions}
import gov.nasa.jpl.omf.scala.core
import gov.nasa.jpl.omf.scala.core.OMFError
import org.semanticweb.owlapi.model.IRI

import scala.collection.immutable.{::, Iterable, List, Map, Nil, Seq, Set}
import scala.{Boolean, Function, None, Option, Some, StringContext, Unit}
import scala.Predef.{ArrowAssoc, String}
import scalaz._
import Scalaz._

object OMLResolver2Ontology {

  type MutableTboxOrDbox = owlapiterminologies.MutableTerminologyBox \/ owlapidescriptions.MutableDescriptionBox

  type Throwables = core.OMFError.Throwables

  type ResolverResult = Throwables \/ OMLResolver2Ontology

  def convert
  (extents: Seq[api.Extent],
   outStore: OWLAPIOMFGraphStore)
  : Throwables \/ OMLResolver2Ontology
  = for {
    out_drc <- outStore.loadBuiltinDatatypeMap()
    om <- outStore.initializeOntologyMapping(out_drc)

    r2oModules <- extents.foldLeft {
      OMLResolver2Ontology(om, outStore).right[OMFError.Throwables]
    } { case (acc, apiExtent) =>
      for {
        prev <- acc
        next <- OMLResolver2Ontology.convertExtent(apiExtent, prev)
      } yield next
    }

    r2o <- convertAllExtents(r2oModules.right)

    tboxConversions <-
    r2o
      .modules
      .foldLeft[OMFError.Throwables \/ (Seq[ImmutableTerminologyBox], OMLResolver2Ontology)] {
      (Seq.empty[ImmutableTerminologyBox], r2o).right
    } {
      case (acc, m0: resolver.api.TerminologyBox) =>
        for {
          prev <- acc
          (convs, r2oPrev) = prev
          m1 <- r2oPrev.getTbox(m0)
          _ = System.out.println(s"... Converting terminology ${m1.sig.kind}: ${m1.iri}")
          next <- r2oPrev.ops.asImmutableTerminologyBox(m1, r2oPrev.om)(outStore).map { case (i1, omWithConv) =>
            (convs :+ i1) -> r2oPrev.copy(om = omWithConv)
          }
        } yield next
      case (acc, _) =>
        acc
    }

    (tboxConvs, r2oTboxConv) = tboxConversions

    _ <- tboxConvs.foldLeft[OMFError.Throwables \/ Unit](().right[OMFError.Throwables]) {
      case (acc, itbox) =>
        for {
          _ <- acc
          _ = System.out.println(s"... Saving terminology ${itbox.iri}")
          _ <- r2o.ops.saveTerminology(itbox)(outStore)
        } yield ()
    }

    dboxConversions <-
    r2o
      .modules
      .foldLeft[OMFError.Throwables \/ (Seq[ImmutableDescriptionBox], OMLResolver2Ontology)] {
      (Seq.empty[ImmutableDescriptionBox], r2oTboxConv).right
    } {
      case (acc, m0: resolver.api.DescriptionBox) =>
        for {
          prev <- acc
          (convs, r2oPrev) = prev
          m1 <- r2oPrev.getDbox(m0)
          _ = System.out.println(s"... Converting description ${m1.sig.kind}: ${m1.iri}")
          next <- r2oPrev.ops.asImmutableDescription(m1, r2oPrev.om)(outStore).map { case (i1, omWithConv) =>
            (convs :+ i1) -> r2oPrev.copy(om = omWithConv)
          }
        } yield next
      case (acc, _) =>
        acc
    }

    (dboxConvs, _) = dboxConversions

    _ <- dboxConvs.foldLeft[OMFError.Throwables \/ Unit](().right[OMFError.Throwables]) {
      case (acc, idbox) =>
        for {
          _ <- acc
          _ = System.out.println(s"... Saving description ${idbox.iri}")
          _ <- r2o.ops.saveDescriptionBox(idbox)(outStore)
        } yield ()
    }
  } yield r2o

  private def convertExtent(extent: api.Extent, prev: OMLResolver2Ontology)
  : Throwables \/ OMLResolver2Ontology
  = for {
    tuple <-
    (extent.terminologyGraphs.toList,
      extent.bundles.toList,
      extent.descriptionBoxes.toList) match {
      case (g :: Nil, Nil, Nil) =>
        Function.tupled(prev.convertTerminologyGraph _)(g)
      case (Nil, b :: Nil, Nil) =>
        Function.tupled(prev.convertBundle _)(b)
      case (Nil, Nil, d :: Nil) =>
        Function.tupled(prev.convertDescriptionBox _)(d)
      case (gs, bs, ds) =>
        Set[java.lang.Throwable](core.OMFError.omfError(
          s"OMLResolver2Text.convert: extent must have a single TerminologyGraph, Bundle or DescriptionBox: " +
            s" got ${gs.size} TerminologyGraphs, ${bs.size} Bundles, ${ds.size} DescriptionBoxes")).left
    }

    (next, m0, m1, i) = tuple

    iri = m0.iri

    updated = next.copy(extents = next.extents + extent)

    result <- if (updated.om.drc.isBuiltInModule(i)(updated.ops)) {
      System.out.println(s"==> OMLResolver2Ontology w/ builtin: $iri")
      updated.withBuiltIn(m0, m1)(extent)
    } else {
      //System.out.println(s"==> OMLResolver2Ontology converting: $iri")
      //convert(updated.right[Throwables], extent, iri, m0, m1, i)
      updated.right
    }
  } yield result

  // , iri: tables.taggedTypes.IRI, m0: api.Module, m1: MutableTboxOrDbox, i: IRI
  private def convertAllExtents(c00: Throwables \/ OMLResolver2Ontology)
  : Throwables \/ OMLResolver2Ontology
  = for {
    // AnnotationProperties
    c10 <- c00
    c11 <- convertAnnotationProperties(c10)
    c1N = c11

    // TerminologyExtensions
    c20 = c1N
    c21 <- convertTerminologyExtensions(c20)
    c2N = c21

    // Atomic Entities
    c30 = c2N
    c31 <- convertAspectOrConcepts(c30)
    c3N = c31

    // Other ModuleEdges
    c40 = c3N
    c41 <- convertConceptDesignationTerminologyAxioms(c40)
    c42 <- convertTerminologyNestingAxioms(c41)
    c43 <- convertBundledTerminologyAxioms(c42)
    c44 <- convertDescriptionBoxExtendsClosedWorldDefinitions(c43)
    c45 <- convertDescriptionBoxRefinements(c44)
    c4N = c41

    // Relationships

    c50 = c4N
    c51 <- convertReifiedRelationships(c50)
    c52 <- convertReifiedRelationshipRestrictions(c51)
    c53 <- convertUnreifiedRelationships(c52)
    c5N = c53

    // DataTypes

    c60 = c5N
    c61 <- convertStructures(c60)
    c62 <- convertScalars(c61)
    c63 <- convertRestrictedDataRanges(c62)
    c64 <- convertScalarOneOfLiteralAxioms(c63)
    c6N = c64

    // DataRelationships

    c70 = c6N
    c71 <- convertEntityScalarDataProperties(c70)
    c72 <- convertEntityStructuredDataProperties(c71)
    c73 <- convertScalarDataProperties(c72)
    c74 <- convertStructuredDataProperties(c73)
    c7N = c74

    // Restrictions

    c80 = c7N
    c81 <- convertEntityRestrictionAxioms(c80)
    c82 <- convertEntityDataPropertyRestrictionAxioms(c81)
    c8N = c82

    // Specializations

    c90 = c8N
    c91 <- convertSpecializationAxioms(c90)
    c92 <- convertSubPropertyOfAxioms(c91)
    c9N = c92

    // Disjunctions

    cA0 = c9N
    cA1 <- convertRootConceptTaxonomyAxioms(cA0)
    cAN = cA1

    cB0 = cAN
    cB1 <- convertChainRules(cB0)
    cBN = cB1

    // ConceptualEntityInstances & UnreifiedRelationshipInstanceTuples
    cC0 = cBN
    cC1 <- convertConceptInstances(cC0)
    cC2 <- convertReifiedRelationshipInstances(cC1)
    cC3 <- convertReifiedRelationshipInstanceDomains(cC2)
    cC4 <- convertReifiedRelationshipInstanceRanges(cC3)
    cC5 <- convertUnreifiedReifiedRelationshipInstanceTuples(cC4)
    cCN = cC5

    // Data Property Values
    cD0 = cBN
    cD1 <- convertSingletonInstanceScalarDataPropertyValues(cD0)
    cD2 <- convertSingletonInstanceStructuredDataPropertyValues(cD1)
    cD3 <- convertStructuredPropertyTuples(cD2)
    cD4 <- convertScalarDataPropertyValues(cD3)
    cDN = cD4

    // Annotations
    cE0 = cDN
    cE1 <- convertAnnotations(cE0)
    cEN = cE1

    // Finished!
    result = cEN
  } yield result

  private def convertTerminologyKind(k: tables.TerminologyKind): core.TerminologyKind = k match {
    case tables.OpenWorldDefinitions =>
      core.TerminologyKind.isOpenWorld
    case tables.ClosedWorldDesignations =>
      core.TerminologyKind.isClosedWorld
  }

  private def convertDescriptionKind(k: tables.DescriptionKind): core.DescriptionKind = k match {
    case tables.Final =>
      core.DescriptionKind.isFinal
    case tables.Partial =>
      core.DescriptionKind.isPartial
  }

  private def convertAnnotationProperties(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.annotationProperties.foldLeft(acc)(convertAnnotationProperty)
  }

  private def convertAnnotationProperty
  : (ResolverResult, (api.Module, Set[api.AnnotationProperty])) => ResolverResult
  = {
    case (acci, (m0, aps0)) =>
      for {
        prev <- acci
        next <- m0 match {
          case t0: api.TerminologyBox =>
            aps0.foldLeft(acci) { case (accj, ap0) =>
              for {
                r2o <- accj
                t1 <- r2o.getTbox(t0)
                ap1 <- r2o.ops.addTerminologyAnnotationProperty(
                  t1,
                  tables.AnnotationProperty(
                    ap0.uuid,
                    t1.uuid,
                    tables.taggedTypes.iri(ap0.iri),
                    tables.taggedTypes.abbrevIRI(ap0.abbrevIRI)))(r2o.omfStore)
                updated = r2o.copy(aps = r2o.aps + (ap0 -> ap1))
              } yield updated
            }
          case d0: api.DescriptionBox =>
            aps0.foldLeft(acci) { case (accj, ap0) =>
              for {
                r2o <- accj
                d1 <- r2o.getDbox(d0)
                ap1 <- r2o.ops.addDescriptionAnnotationProperty(
                  d1,
                  tables.AnnotationProperty(
                    ap0.uuid,
                    d1.uuid,
                    tables.taggedTypes.iri(ap0.iri),
                    tables.taggedTypes.abbrevIRI(ap0.abbrevIRI)))(r2o.omfStore)
                updated = r2o.copy(aps = r2o.aps + (ap0 -> ap1))
              } yield updated
            }
        }
      } yield next
  }

  private def convertAnnotations(r2o: OMLResolver2Ontology)
  : ResolverResult
  =  r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.annotations.foldLeft(acc)(convertAnnotations(ext))
  }

  private def convertAnnotations(ext: api.Extent)
  : (ResolverResult, (api.LogicalElement, Set[api.AnnotationPropertyValue])) => ResolverResult
  = {
    case (acc, (e0, apvs)) =>
      for {
        r2o <- acc
        _ <- e0 match {
          case x0: api.Module =>
            r2o.getModule(x0).flatMap { x1 =>
              convertElementAnnotations(r2o, x1, x1, apvs)
            }
          case x0: api.ModuleElement =>
            r2o.lookupModuleElement(x0)(ext).flatMap { x1 =>
              x0.moduleContext()(ext) match {
                case Some(m0) =>
                  r2o.getModule(m0).flatMap { m1 =>
                    convertElementAnnotations(r2o, m1, x1, apvs)
                  }
                case _ =>
                  Set[java.lang.Throwable](new IllegalArgumentException(
                    s"OMLResolver2Ontology.convertAnnotations(subject=$x0) not found!")).left
              }
            }
          case x0: api.ModuleEdge =>
            // @TODO
            ().right[Throwables]
          case x0: api.LogicalElement =>
            r2o.lookupOtherElement(x0)(ext).flatMap { x1 =>
              x0.moduleContext()(ext) match {
                case Some(m0) =>
                  r2o.getModule(m0).flatMap { m1 =>
                    convertElementAnnotations(r2o, m1, x1, apvs)
                  }
                case _ =>
                  Set[java.lang.Throwable](new IllegalArgumentException(
                    s"OMLResolver2Ontology.convertAnnotations(subject=$x0) not found!")).left
              }
            }
        }
      } yield r2o
  }

  private def convertElementAnnotations
  (r2o: OMLResolver2Ontology,
   m: owlapi.common.MutableModule,
   e: owlapi.common.LogicalElement,
   apvs: Set[api.AnnotationPropertyValue])
  : Throwables \/ Unit
  = apvs.foldLeft(().right[Throwables]) { case (acc, apv) =>
    for {
      _ <- acc
      ap <- r2o.aps.get(apv.property) match {
        case Some(p) =>
          p.right[Throwables]
        case _ =>
          Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
            s"OWLResolver2Ontology.convertModuleAnnotations(${apv.property}) not found."
          )).left
      }
      _ <- m.addAnnotation(e, ap, apv.value)(r2o.omfStore)
    } yield ()
  }

  // Atomic Entities

  private def convertAspectOrConcepts(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o
    .extents
    .flatMap(_.terminologyBoxOfTerminologyBoxStatement)
    .foldLeft(r2o.right[Throwables])(convertAspectOrConcept)

  private val convertAspectOrConcept
  : (ResolverResult, (api.TerminologyBoxStatement, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (a0: api.Aspect, t0: api.TerminologyBox)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        a1 <- r2o.ops.addAspect(t1, tables.taggedTypes.localName(a0.name))(r2o.omfStore)
      } yield r2o.copy(aspects = r2o.aspects + (a0 -> a1))
    case (acc, (c0: api.Concept, t0: api.TerminologyBox)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        c1 <- r2o.ops.addConcept(t1, tables.taggedTypes.localName(c0.name))(r2o.omfStore)
      } yield r2o.copy(concepts = r2o.concepts + (c0 -> c1))
    case (acc, _) =>
      acc
  }

  // ModuleEdges

  private def convertTerminologyExtensions(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o
    .extents
    .flatMap(_.terminologyBoxOfTerminologyBoxAxiom)
    .foldLeft(r2o.right[Throwables])(convertTerminologyExtension)

  private def convertTerminologyExtension
  : (ResolverResult, (api.TerminologyBoxAxiom, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (ax0: api.TerminologyExtensionAxiom, t0)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        e1 <- r2o.getTboxByIRI(ax0.extendedTerminology)
        //_ = System.out.println(s"convertTerminologyExtension($iri)\n\textendingG=${t1.iri}\n\t  extendedG=${e1.iri}")
        ax1 <- r2o.ops.addTerminologyExtension(extendingG = t1, extendedG = e1)(r2o.omfStore)
      } yield r2o.copy(edges = r2o.edges + (ax0 -> ax1))
    case (acc, _) =>
      acc
  }

  private def convertConceptDesignationTerminologyAxioms(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.terminologyBoxOfTerminologyBoxAxiom.foldLeft(acc)(convertConceptDesignationTerminologyAxiom(ext))
  }

  private def convertConceptDesignationTerminologyAxiom(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBoxAxiom, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (ax0: api.ConceptDesignationTerminologyAxiom, t0)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        dt1 <- r2o.getTboxByIRI(ax0.designatedTerminology)
        dc1 <- r2o.lookupConcept(ax0.designatedConcept)
        ax1 <- r2o.ops.addEntityConceptDesignationTerminologyAxiom(t1, dc1, dt1)(r2o.omfStore)
      } yield r2o.copy(edges = r2o.edges + (ax0 -> ax1))
    case (acc, _) =>
      acc
  }

  private def convertTerminologyNestingAxioms(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.terminologyBoxOfTerminologyBoxAxiom.foldLeft(acc)(convertTerminologyNestingAxiom(ext))
  }

  private def convertTerminologyNestingAxiom(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBoxAxiom, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (ax0: api.TerminologyNestingAxiom, g0)) =>
      for {
        r2o <- acc
        g1 <- r2o.getGbox(g0)
        nt1 <- r2o.getTboxByIRI(ax0.nestingTerminology)
        nc1 <- r2o.lookupConcept(ax0.nestingContext)
        ax1 <- r2o.ops.addNestedTerminology(nt1, nc1, g1)(r2o.omfStore)
      } yield r2o.copy(edges = r2o.edges + (ax0 -> ax1))
    case (acc, _) =>
      acc
  }

  private def convertBundledTerminologyAxioms(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.bundleOfTerminologyBundleAxiom.foldLeft(acc)(convertBundledTerminologyAxiom(ext))
  }

  private def convertBundledTerminologyAxiom(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBundleAxiom, api.Bundle)) => ResolverResult
  = {
    case (acc, (ax0: api.BundledTerminologyAxiom, b0)) =>
      for {
        r2o <- acc
        b1 <- r2o.getBundle(b0)
        t1 <- r2o.getTboxByIRI(ax0.bundledTerminology)
        ax1 <- r2o.ops.addBundledTerminologyAxiom(b1, t1)(r2o.omfStore)
      } yield r2o.copy(edges = r2o.edges + (ax0 -> ax1))
    case (acc, _) =>
      acc
  }

  private def convertDescriptionBoxExtendsClosedWorldDefinitions(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.descriptionBoxOfDescriptionBoxExtendsClosedWorldDefinitions.foldLeft(acc)(convertDescriptionBoxExtendsClosedWorldDefinition(ext))
  }

  private def convertDescriptionBoxExtendsClosedWorldDefinition(implicit ext: api.Extent)
  : (ResolverResult, (api.DescriptionBoxExtendsClosedWorldDefinitions, api.DescriptionBox)) => ResolverResult
  = {
    case (acc, (ax0, d0)) =>
      for {
        r2o <- acc
        d1 <- r2o.getDbox(d0)
        t1 <- r2o.getTboxByIRI(ax0.closedWorldDefinitions)
        ax1 <- r2o.ops.addDescriptionBoxExtendsClosedWorldDefinitions(d1, t1)(r2o.omfStore)
      } yield r2o.copy(edges = r2o.edges + (ax0 -> ax1))
    case (acc, _) =>
      acc
  }

  private def convertDescriptionBoxRefinements(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.descriptionBoxOfDescriptionBoxRefinement.foldLeft(acc)(convertDescriptionBoxRefinement(ext))
  }

  private def convertDescriptionBoxRefinement(implicit ext: api.Extent)
  : (ResolverResult, (api.DescriptionBoxRefinement, api.DescriptionBox)) => ResolverResult
  = {
    case (acc, (ax0, d0)) =>
      for {
        r2o <- acc
        d1 <- r2o.getDbox(d0)
        rd1 <- r2o.getDboxByIRI(ax0.refinedDescriptionBox)
        ax1 <- r2o.ops.addDescriptionBoxRefinement(d1, rd1)(r2o.omfStore)
      } yield r2o.copy(edges = r2o.edges + (ax0 -> ax1))
    case (acc, _) =>
      acc
  }

  // Relationships

  private def convertReifiedRelationshipRestrictions(r2o: OMLResolver2Ontology)
  : ResolverResult
  = {
    val tuples = r2o.extents.to[Seq].flatMap { ext =>
      ext
        .terminologyBoxOfTerminologyBoxStatement
        .selectByKindOf { case (rr: api.ReifiedRelationshipRestriction, t: api.TerminologyBox) => (ext, rr, t) }
    }

    convertReifiedRelationshipRestrictions(r2o.right, tuples, List.empty)(r2o.omfStore)
  }

  private def convertReifiedRelationshipRestrictions
  (acc: ResolverResult,
   rrs: Iterable[(api.Extent, api.ReifiedRelationshipRestriction, api.TerminologyBox)],
   queue: List[(api.Extent, api.ReifiedRelationshipRestriction, api.TerminologyBox)],
   progress: Boolean = false)
  (implicit omfStore: owlapi.OWLAPIOMF#Store)
  : ResolverResult
  = if (rrs.isEmpty) {
    if (queue.isEmpty)
      acc
    else if (progress)
      convertReifiedRelationshipRestrictions(acc, queue, List.empty)
    else
      Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
        ConversionCommand.explainProblems(
          s"convertReifiedRelationshipRestrictions: no progress with ${queue.size} reified relationships in the queue",
          queue.map(_._2.name)))).left
  } else acc match {
    case \/-(r2o) =>
      val (ext, rr0, t0) = rrs.head
      ( r2o.lookupEntity(rr0.source),
        r2o.lookupEntity(rr0.target)) match {
        case (Some(rs1), Some(rt1)) =>
         val updated = for {
            t1 <- r2o.getTbox(t0)
            rr1 <- r2o.ops.addReifiedRelationshipRestriction(
              t1,
              tables.taggedTypes.localName(rr0.name),
              rs1, rt1)

          } yield r2o.copy(
            reifiedRelationshipRestrictions = r2o.reifiedRelationshipRestrictions + (rr0 -> rr1))

          convertReifiedRelationshipRestrictions(updated, rrs.tail, queue, progress = true)

        case (_, _) =>
          val rest = rrs.tail
          if (rest.isEmpty)
            convertReifiedRelationshipRestrictions(acc, rrs.head :: queue, List.empty, progress)
          else
            convertReifiedRelationshipRestrictions(acc, rest, rrs.head :: queue)
      }

    case _ =>
      acc
  }

  private def convertReifiedRelationships(r2o: OMLResolver2Ontology)
  : ResolverResult
  = {
    val tuples = r2o.extents.to[Seq].flatMap { ext =>
      ext
        .terminologyBoxOfTerminologyBoxStatement
        .selectByKindOf { case (rr: api.ReifiedRelationship, t: api.TerminologyBox) => (ext, rr, t) }
    }

    convertReifiedRelationships(r2o.right, tuples, List.empty)(r2o.omfStore)
  }

  private def convertReifiedRelationships
  (acc: ResolverResult,
   rrs: Iterable[(api.Extent, api.ReifiedRelationship, api.TerminologyBox)],
   queue: List[(api.Extent, api.ReifiedRelationship, api.TerminologyBox)],
   progress: Boolean = false)
  (implicit omfStore: owlapi.OWLAPIOMF#Store)
  : ResolverResult
  = if (rrs.isEmpty) {
    if (queue.isEmpty)
      acc
    else if (progress)
      convertReifiedRelationships(acc, queue, List.empty)
    else
      Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
        ConversionCommand.explainProblems(
          s"convertReifiedRelationships: no progress with ${queue.size} reified relationships in the queue",
          queue.map(_._2.name)))).left
  } else acc match {
    case \/-(r2o) =>
      val (ext, rr0, t0) = rrs.head
      val ns0 = rr0.allNestedElements()(ext)
      ( ns0.selectByKindOf { case f: api.ForwardProperty => f }.headOption,
        r2o.lookupEntity(rr0.source),
        r2o.lookupEntity(rr0.target)) match {
        case (Some(fwd0), Some(rs1), Some(rt1)) =>
          val inv0 = ns0.selectByKindOf { case i: api.InverseProperty => i }.headOption
          val updated = for {
            t1 <- r2o.getTbox(t0)
            rr1 <- r2o.ops.addReifiedRelationship(
              t1, rs1, rt1,
              Iterable() ++
                (if (rr0.isAsymmetric)
                  Iterable(core.RelationshipCharacteristics.isAsymmetric)
                else Iterable()) ++
                (if (rr0.isEssential)
                  Iterable(core.RelationshipCharacteristics.isEssential)
                else Iterable()) ++
                (if (rr0.isFunctional)
                  Iterable(core.RelationshipCharacteristics.isFunctional)
                else Iterable()) ++
                (if (rr0.isInverseEssential)
                  Iterable(core.RelationshipCharacteristics.isInverseEssential)
                else Iterable()) ++
                (if (rr0.isInverseFunctional)
                  Iterable(core.RelationshipCharacteristics.isInverseFunctional)
                else Iterable()) ++
                (if (rr0.isIrreflexive)
                  Iterable(core.RelationshipCharacteristics.isIrreflexive)
                else Iterable()) ++
                (if (rr0.isReflexive)
                  Iterable(core.RelationshipCharacteristics.isReflexive)
                else Iterable()) ++
                (if (rr0.isSymmetric)
                  Iterable(core.RelationshipCharacteristics.isSymmetric)
                else Iterable()) ++
                (if (rr0.isTransitive)
                  Iterable(core.RelationshipCharacteristics.isTransitive)
                else Iterable()),
              tables.taggedTypes.localName(rr0.name),
              fwd0.name,
              inv0.map(_.name)
            )
            invPair <- (inv0, rr1.inverseProperty) match {
              case (Some(i0), Some(i1)) =>
                Some(i0 -> i1).right
              case (None, None) =>
                None.right
              case _ =>
                -\/(Set[java.lang.Throwable](new IllegalArgumentException(
                  s"convertReifiedRelationship: inconsistent inverse properties for ${rr0.abbrevIRI()(ext)}"
                )))
            }
          } yield r2o.copy(
            reifiedRelationships = r2o.reifiedRelationships + (rr0 -> rr1),
            forwardProperties = r2o.forwardProperties + (fwd0 -> rr1.forwardProperty),
            inverseProperties = r2o.inverseProperties ++ invPair)

          convertReifiedRelationships(updated, rrs.tail, queue, progress = true)

        case (Some(_), _, _) =>
          val rest = rrs.tail
          if (rest.isEmpty)
            convertReifiedRelationships(acc, rrs.head :: queue, List.empty, progress)
          else
            convertReifiedRelationships(acc, rest, rrs.head :: queue)

        case (None, _, _) =>
          Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
            s"convertReifiedRelationships: Failed to find a ForwardProperty for ReifiedRelationship: ${rr0.iri()(ext)}"
          )).left
      }

    case _ =>
      acc
  }

  private def convertUnreifiedRelationships(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.terminologyBoxOfTerminologyBoxStatement.foldLeft(acc)(convertUnreifiedRelationship(ext))
  }

  private def convertUnreifiedRelationship(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBoxStatement, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (ur0: api.UnreifiedRelationship, t0)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        us1 <- r2o.entityLookup(ur0.source)
        ut1 <- r2o.entityLookup(ur0.target)
        ur1 <- r2o.ops.addUnreifiedRelationship(
          t1, us1, ut1,
          Iterable() ++
            (if (ur0.isAsymmetric)
              Iterable(core.RelationshipCharacteristics.isAsymmetric)
            else Iterable()) ++
            (if (ur0.isEssential)
              Iterable(core.RelationshipCharacteristics.isEssential)
            else Iterable()) ++
            (if (ur0.isFunctional)
              Iterable(core.RelationshipCharacteristics.isFunctional)
            else Iterable()) ++
            (if (ur0.isInverseEssential)
              Iterable(core.RelationshipCharacteristics.isInverseEssential)
            else Iterable()) ++
            (if (ur0.isInverseFunctional)
              Iterable(core.RelationshipCharacteristics.isInverseFunctional)
            else Iterable()) ++
            (if (ur0.isIrreflexive)
              Iterable(core.RelationshipCharacteristics.isIrreflexive)
            else Iterable()) ++
            (if (ur0.isReflexive)
              Iterable(core.RelationshipCharacteristics.isReflexive)
            else Iterable()) ++
            (if (ur0.isTransitive)
              Iterable(core.RelationshipCharacteristics.isTransitive)
            else Iterable()),
          tables.taggedTypes.localName(ur0.name)
        )(r2o.omfStore)
      } yield r2o.copy(unreifiedRelationships = r2o.unreifiedRelationships + (ur0 -> ur1))
    case (acc, _) =>
      acc
  }

  // DataTypes

  private def convertStructures(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.terminologyBoxOfTerminologyBoxStatement.foldLeft(acc)(convertStructure(ext))
  }

  private def convertStructure(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBoxStatement, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (s0: api.Structure, t0)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        s1 <- r2o.ops.addStructuredDataType(t1, tables.taggedTypes.localName(s0.name))(r2o.omfStore)
      } yield r2o.copy(structures = r2o.structures + (s0 -> s1))
    case (acc, _) =>
      acc
  }

  private def convertScalars(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.terminologyBoxOfTerminologyBoxStatement.foldLeft(acc)(convertScalar(ext))
  }

  private def convertScalar(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBoxStatement, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (s0: api.Scalar, t0)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        s1 <- r2o.ops.addScalarDataType(t1, tables.taggedTypes.localName(s0.name))(r2o.omfStore)
      } yield r2o.copy(dataRanges = r2o.dataRanges + (s0 -> s1))
    case (acc, _) =>
      acc
  }

  private def convertRestrictedDataRanges
  (r2o: OMLResolver2Ontology)
  : ResolverResult
  = {
    val drs =
      r2o
        .extents
        .flatMap { ext =>
          ext.terminologyBoxOfTerminologyBoxStatement.selectByKindOf { case (dr: api.RestrictedDataRange, t: api.TerminologyBox) => dr -> t }
        }

    convertRestrictedDataRanges(r2o.right, drs, List.empty)(r2o.omfStore)
  }

  @scala.annotation.tailrec
  private def convertRestrictedDataRanges
  (acc: ResolverResult,
   drs: Iterable[(api.RestrictedDataRange, api.TerminologyBox)],
   queue: List[(api.RestrictedDataRange, api.TerminologyBox)],
   progress: Boolean = false)
  (implicit omfStore: owlapi.OWLAPIOMF#Store)
  : ResolverResult
  = if (drs.isEmpty) {
    if (queue.isEmpty)
      acc
    else if (progress)
      convertRestrictedDataRanges(acc, queue, List.empty)
    else
      Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
        ConversionCommand.explainProblems(
          s"convertRestrictedDataRanges: no progress with ${queue.size} data ranges in the queue",
          queue.map(_._1.name)))).left
  }
  else acc match {
    case \/-(r2o) =>
      val (dr0, t0) = drs.head
      (r2o.lookupTbox(t0), r2o.dataRanges.get(dr0.restrictedRange)) match {
        case (Some(t1), Some(rr1)) =>
          val dr1 = dr0 match {
            case rdr0: api.BinaryScalarRestriction =>
              r2o.ops
                .addBinaryScalarRestriction(
                  t1, tables.taggedTypes.localName(rdr0.name), rr1,
                  rdr0.length, rdr0.minLength, rdr0.maxLength)
                .map { rdr1 => r2o.copy(dataRanges = r2o.dataRanges + (rdr0 -> rdr1)) }
            case rdr0: api.IRIScalarRestriction =>
              r2o.ops
                .addIRIScalarRestriction(
                  t1, tables.taggedTypes.localName(rdr0.name), rr1,
                  rdr0.length, rdr0.minLength, rdr0.maxLength, rdr0.pattern)
                .map { rdr1 => r2o.copy(dataRanges = r2o.dataRanges + (rdr0 -> rdr1)) }
            case rdr0: api.NumericScalarRestriction =>
              r2o.ops
                .addNumericScalarRestriction(
                  t1, tables.taggedTypes.localName(rdr0.name), rr1,
                  rdr0.minInclusive, rdr0.maxInclusive,
                  rdr0.minExclusive, rdr0.maxExclusive)
                .map { rdr1 => r2o.copy(dataRanges = r2o.dataRanges + (rdr0 -> rdr1)) }
            case rdr0: api.PlainLiteralScalarRestriction =>
              r2o.ops
                .addPlainLiteralScalarRestriction(
                  t1, tables.taggedTypes.localName(rdr0.name), rr1,
                  rdr0.length, rdr0.minLength, rdr0.maxLength, rdr0.pattern,
                  rdr0.langRange.map(r => tables.taggedTypes.languageTagDataType(r)))
                .map { rdr1 => r2o.copy(dataRanges = r2o.dataRanges + (rdr0 -> rdr1)) }
            case rdr0: api.ScalarOneOfRestriction =>
              r2o.ops
                .addScalarOneOfRestriction(
                  t1, tables.taggedTypes.localName(rdr0.name), rr1)
                .map { rdr1 => r2o.copy(dataRanges = r2o.dataRanges + (rdr0 -> rdr1)) }
            case rdr0: api.StringScalarRestriction =>
              r2o.ops
                .addStringScalarRestriction(
                  t1, tables.taggedTypes.localName(rdr0.name), rr1,
                  rdr0.length, rdr0.minLength, rdr0.maxLength, rdr0.pattern)
                .map { rdr1 => r2o.copy(dataRanges = r2o.dataRanges + (rdr0 -> rdr1)) }
            case rdr0: api.SynonymScalarRestriction =>
              r2o.ops
                .addSynonymScalarRestriction(
                  t1, tables.taggedTypes.localName(rdr0.name), rr1)
                .map { rdr1 => r2o.copy(dataRanges = r2o.dataRanges + (rdr0 -> rdr1)) }
            case rdr0: api.TimeScalarRestriction =>
              r2o.ops
                .addTimeScalarRestriction(
                  t1, tables.taggedTypes.localName(rdr0.name), rr1,
                  rdr0.minInclusive, rdr0.maxInclusive,
                  rdr0.minExclusive, rdr0.maxExclusive)
                .map { rdr1 => r2o.copy(dataRanges = r2o.dataRanges + (rdr0 -> rdr1)) }
          }
          dr1 match {
            case \/-(next) =>
              convertRestrictedDataRanges(\/-(next), drs.tail, queue, progress = true)
            case -\/(errors) =>
              -\/(errors)
          }
        case (Some(_), None) =>
          val rest = drs.tail
          if (rest.isEmpty)
            convertRestrictedDataRanges(acc, drs.head :: queue, List.empty, progress)
          else
            convertRestrictedDataRanges(acc, rest, drs.head :: queue)
        case (None, _) =>
          Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
            s"convertRestrictedDataRanges: Failed to resolve " +
              s"tbox: $t0")).left
      }
    case _ =>
      acc
  }

  private def convertScalarOneOfLiteralAxioms(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.terminologyBoxOfTerminologyBoxStatement.foldLeft(acc)(convertScalarOneOfLiteralAxiom(ext))
  }

  private def convertScalarOneOfLiteralAxiom(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBoxStatement, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (s0: api.ScalarOneOfLiteralAxiom, t0)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        r1 <- r2o.lookupScalarOneOfRestriction(s0.axiom)
        vt1 <- s0.valueType match {
          case Some(vt0) =>
            r2o.lookupDataRange(vt0).map(Option.apply)
          case None =>
            None.right
        }
        s1 <- r2o.ops.addScalarOneOfLiteralAxiom(t1, r1, s0.value, vt1)(r2o.omfStore)
      } yield r2o.copy(termAxioms = r2o.termAxioms + (s0 -> s1))
    case (acc, _) =>
      acc
  }

  // DataRelationships

  private def convertEntityScalarDataProperties(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.terminologyBoxOfTerminologyBoxStatement.foldLeft(acc)(convertEntityScalarDataProperty(ext))
  }

  private def convertEntityScalarDataProperty(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBoxStatement, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (dp0: api.EntityScalarDataProperty, t0)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        e1 <- r2o.entityLookup(dp0.domain)
        r1 <- r2o.lookupDataRange(dp0.range)
        dp1 <- r2o.ops.addEntityScalarDataProperty(
          t1, e1, r1,
          tables.taggedTypes.localName(dp0.name),
          dp0.isIdentityCriteria)(r2o.omfStore)
      } yield r2o.copy(entityScalarDataProperties = r2o.entityScalarDataProperties + (dp0 -> dp1))
    case (acc, _) =>
      acc
  }

  private def convertEntityStructuredDataProperties(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.terminologyBoxOfTerminologyBoxStatement.foldLeft(acc)(convertEntityStructuredDataProperty(ext))
  }

  private def convertEntityStructuredDataProperty(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBoxStatement, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (dp0: api.EntityStructuredDataProperty, t0)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        e1 <- r2o.entityLookup(dp0.domain)
        r1 <- r2o.lookupStructure(dp0.range)
        dp1 <- r2o.ops.addEntityStructuredDataProperty(
          t1, e1, r1,
          tables.taggedTypes.localName(dp0.name),
          dp0.isIdentityCriteria)(r2o.omfStore)
      } yield r2o.copy(entityStructuredDataProperties = r2o.entityStructuredDataProperties + (dp0 -> dp1))
    case (acc, _) =>
      acc
  }

  private def convertScalarDataProperties(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.terminologyBoxOfTerminologyBoxStatement.foldLeft(acc)(convertScalarDataProperty(ext))
  }

  private def convertScalarDataProperty(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBoxStatement, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (dp0: api.ScalarDataProperty, t0)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        e1 <- r2o.lookupStructure(dp0.domain)
        r1 <- r2o.lookupDataRange(dp0.range)
        dp1 <- r2o.ops.addScalarDataProperty(
          t1, e1, r1,
          tables.taggedTypes.localName(dp0.name))(r2o.omfStore)
      } yield r2o.copy(scalarDataProperties = r2o.scalarDataProperties + (dp0 -> dp1))
    case (acc, _) =>
      acc
  }

  private def convertStructuredDataProperties(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.terminologyBoxOfTerminologyBoxStatement.foldLeft(acc)(convertStructuredDataProperty(ext))
  }

  private def convertStructuredDataProperty(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBoxStatement, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (dp0: api.StructuredDataProperty, t0)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        e1 <- r2o.lookupStructure(dp0.domain)
        r1 <- r2o.lookupStructure(dp0.range)
        dp1 <- r2o.ops.addStructuredDataProperty(
          t1, e1, r1,
          tables.taggedTypes.localName(dp0.name))(r2o.omfStore)
      } yield r2o.copy(structuredDataProperties = r2o.structuredDataProperties + (dp0 -> dp1))
    case (acc, _) =>
      acc
  }

  // Entity Restrictions

  private def convertEntityRestrictionAxioms(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.terminologyBoxOfTerminologyBoxStatement.foldLeft(acc)(convertEntityRestrictionAxiom(ext))
  }

  private def convertEntityRestrictionAxiom(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBoxStatement, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (er0: api.EntityRestrictionAxiom, t0)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        d1 <- r2o.entityLookup(er0.restrictedDomain)
        r1 <- r2o.entityLookup(er0.restrictedRange)
        rel1 <- r2o.restrictableRelationshipLookup(er0.restrictedRelationship)
        er1 <- er0 match {
          case _: api.EntityExistentialRestrictionAxiom =>
            r2o.ops.addEntityExistentialRestrictionAxiom(t1, d1, rel1, r1)(r2o.omfStore)
          case _: api.EntityUniversalRestrictionAxiom =>
            r2o.ops.addEntityUniversalRestrictionAxiom(t1, d1, rel1, r1)(r2o.omfStore)
        }
      } yield r2o.copy(termAxioms = r2o.termAxioms + (er0 -> er1))
    case (acc, _) =>
      acc
  }

  private def convertEntityDataPropertyRestrictionAxioms(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.terminologyBoxOfTerminologyBoxStatement.foldLeft(acc)(convertEntityDataPropertyRestrictionAxiom(ext))
  }

  private def convertEntityDataPropertyRestrictionAxiom(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBoxStatement, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (er0: api.EntityScalarDataPropertyRestrictionAxiom, t0)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        d1 <- r2o.entityLookup(er0.restrictedEntity)
        sc1 <- r2o.lookupEntityScalarDataProperty(er0.scalarProperty)
        er1 <- er0 match {
          case ep0: api.EntityScalarDataPropertyExistentialRestrictionAxiom =>
            r2o.lookupDataRange(ep0.scalarRestriction).flatMap { r1 =>
              r2o.ops.addEntityScalarDataPropertyExistentialRestrictionAxiom(t1, d1, sc1, r1)(r2o.omfStore)
            }
          case ep0: api.EntityScalarDataPropertyUniversalRestrictionAxiom =>
            r2o.lookupDataRange(ep0.scalarRestriction).flatMap { r1 =>
              r2o.ops.addEntityScalarDataPropertyUniversalRestrictionAxiom(t1, d1, sc1, r1)(r2o.omfStore)
            }
          case ep0: api.EntityScalarDataPropertyParticularRestrictionAxiom =>
            for {
              vt1 <- ep0.valueType match {
                case Some(vt0) =>
                  r2o.lookupDataRange(vt0).map(Option.apply)
                case None =>
                  None.right
              }
              ep1 <- r2o.ops.addEntityScalarDataPropertyParticularRestrictionAxiom(t1, d1, sc1, ep0.literalValue, vt1)(r2o.omfStore)
            } yield ep1
        }
      } yield r2o.copy(termAxioms = r2o.termAxioms + (er0 -> er1))
    case (acc, (er0: api.EntityStructuredDataPropertyParticularRestrictionAxiom, t0)) =>
      for {
        r2o1 <- acc
        t1 <- r2o1.getTbox(t0)
        d1 <- r2o1.entityLookup(er0.restrictedEntity)
        sc1 <- r2o1.lookupEntityStructuredDataProperty(er0.structuredDataProperty)
        er1 <- r2o1.ops.addEntityStructuredDataPropertyParticularRestrictionAxiom(t1, d1, sc1)(r2o1.omfStore)
        erTuple = er0 -> er1
        r2o2 <- convertRestrictionStructuredDataPropertyContext(r2o1.copy(termAxioms = r2o1.termAxioms + erTuple), t1, Seq(erTuple))
      } yield r2o2
    case (acc, _) =>
      acc
  }

  @scala.annotation.tailrec
  protected final def convertRestrictionStructuredDataPropertyContext
  (r2o: OMLResolver2Ontology,
   t1: owlapi.types.terminologies.MutableTerminologyBox,
   cs: Seq[(api.RestrictionStructuredDataPropertyContext, owlapi.types.RestrictionStructuredDataPropertyContext)])
  (implicit ext: api.Extent)
  : Throwables \/ OMLResolver2Ontology
  = if (cs.isEmpty)
    r2o.right[Throwables]
  else {
    val (c0, c1) = cs.head
    val values = ext
      .restrictionStructuredDataPropertyContextOfRestrictionScalarDataPropertyValue
      .foldLeft(r2o.right[Throwables]) { case (acc, (vi, ci)) =>
        if (c0 != ci)
          acc
        else
          for {
            r2o1 <- acc
            ont_dp <- r2o1.lookupDataRelationshipToScalar(vi.scalarDataProperty)
            ont_vt <- vi.valueType match {
              case Some(dt) =>
                r2o1.lookupDataRange(dt).map(Option.apply)
              case None =>
                Option.empty.right[Throwables]
            }
            vj <- r2o1.ops.addRestrictionScalarDataPropertyValue(t1, c1, ont_dp, vi.scalarPropertyValue, ont_vt)(r2o1.omfStore)
          } yield r2o1.copy(restrictionScalarDataPropertyValues = r2o1.restrictionScalarDataPropertyValues + (vi -> vj))
      }

    val tuples = ext
      .restrictionStructuredDataPropertyContextOfRestrictionStructuredDataPropertyTuple
      .foldLeft(values.map(_ -> cs.tail)) { case (acc, (ti, ci)) =>
        if (c0 != ci)
          acc
        else
          for {
            r2o1_cs1 <- acc
            (r2o1, cs1) = r2o1_cs1
            ont_dp <- r2o1.lookupDataRelationshipToStructure(ti.structuredDataProperty)
            ont_ti <- r2o1.ops.addRestrictionStructuredDataPropertyTuple(t1, c1, ont_dp)(r2o1.omfStore)
            r2o2 = r2o1.copy(restrictionStructuredDataPropertyTuples = r2o1.restrictionStructuredDataPropertyTuples + (ti -> ont_ti))
            cs2 = cs1 :+ (ti -> ont_ti)
          } yield r2o2 -> cs2
      }


    tuples match {
      case \/-((r2o1, next)) =>
        convertRestrictionStructuredDataPropertyContext(r2o1, t1, next)
      case -\/(errors) =>
        -\/(errors)
    }
  }

  // Specializations

  private def convertSpecializationAxioms(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.terminologyBoxOfTerminologyBoxStatement.foldLeft(acc)(convertSpecializationAxiom(ext))
  }

  private def convertSpecializationAxiom(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBoxStatement, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (ax0: api.SpecializationAxiom, t0)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        p1 <- r2o.entityLookup(ax0.parent())
        c1 <- r2o.entityLookup(ax0.child())
        ax1 <- (ax0, p1, c1) match {
          case (_: api.ConceptSpecializationAxiom, sup: owlapi.types.terms.Concept, sub: owlapi.types.terms.Concept) =>
            r2o.ops.addConceptSpecializationAxiom(t1, sub, sup)(r2o.omfStore)
          case (_: api.AspectSpecializationAxiom, sup: owlapi.types.terms.Aspect, sub) =>
            r2o.ops.addAspectSpecializationAxiom(t1, sub, sup)(r2o.omfStore)
          case (_: api.ReifiedRelationshipSpecializationAxiom, sup: owlapi.types.terms.ConceptualRelationship, sub: owlapi.types.terms.ConceptualRelationship) =>
            r2o.ops.addReifiedRelationshipSpecializationAxiom(t1, sub, sup)(r2o.omfStore)
          case _ =>
            Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
              s"convertSpecializationAxiom: Failed to resolve " +
                s"tbox: $t0" +
                s" for defining SpecializationAxiom: $ax0")).left
        }
      } yield r2o.copy(termAxioms = r2o.termAxioms + (ax0 -> ax1))
    case (acc, _) =>
      acc
  }

  // Sub{Data|Object}PropertyOfAxioms

  private def convertSubPropertyOfAxioms(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.terminologyBoxOfTerminologyBoxStatement.foldLeft(acc)(convertSubPropertyOfAxiom(ext))
  }

  private def convertSubPropertyOfAxiom(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBoxStatement, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (ax0: api.SubDataPropertyOfAxiom, t0)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        sub1 <- r2o.lookupEntityScalarDataProperty(ax0.subProperty)
        sup1 <- r2o.lookupEntityScalarDataProperty(ax0.superProperty)
        ax1 <- r2o.ops.addSubDataPropertyOfAxiom(t1, sub1, sup1)(r2o.omfStore)
      } yield r2o.copy(termAxioms = r2o.termAxioms + (ax0 -> ax1))

    case (acc, (ax0: api.SubObjectPropertyOfAxiom, t0)) =>
      for {
        r2o <- acc
        t1 <- r2o.getTbox(t0)
        sub1 <- r2o.lookupUnreifiedRelationship(ax0.subProperty)
        sup1 <- r2o.lookupUnreifiedRelationship(ax0.superProperty)
        ax1 <- r2o.ops.addSubObjectPropertyOfAxiom(t1, sub1, sup1)(r2o.omfStore)
      } yield r2o.copy(termAxioms = r2o.termAxioms + (ax0 -> ax1))

    case (acc, _) =>
      acc
  }

  // Disjunctions

  private def convertRootConceptTaxonomyAxioms(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.terminologyBoxOfTerminologyBoxStatement.foldLeft(acc)(convertRootConceptTaxonomyAxiom(ext))
  }

  private def convertRootConceptTaxonomyAxiom(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBoxStatement, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (ax0: api.RootConceptTaxonomyAxiom, b0: api.Bundle)) =>
      for {
        r2o <- acc
        b1 <- r2o.getBundle(b0)
        r1 <- r2o.lookupConcept(ax0.root)
        ax1 <- r2o.ops.addRootConceptTaxonomyAxiom(b1, r1)(r2o.omfStore)
        disjuncts = disjunctsForConceptTreeDisjunction(r2o, ax0, ax1)
        next <- convertConceptTreeDisjunctions(r2o, b1, ax0, disjuncts)
      } yield next
    case (acc, _) =>
      acc
  }

  private def disjunctsForConceptTreeDisjunction
  (r2o: OMLResolver2Ontology, ctd0: api.ConceptTreeDisjunction, ctd1: owlapi.types.bundleStatements.ConceptTreeDisjunction)
  (implicit ext: api.Extent)
  : Seq[(api.DisjointUnionOfConceptsAxiom, owlapi.types.bundleStatements.ConceptTreeDisjunction)]
  = ext.disjunctions.getOrElse(ctd0, Set.empty).to[Seq].map(_ -> ctd1)

  @scala.annotation.tailrec
  private def convertConceptTreeDisjunctions
  (r2o: OMLResolver2Ontology,
   b1: owlapi.types.terminologies.MutableBundle,
   ax0: api.ConceptTreeDisjunction,
   disjuncts: Seq[(api.DisjointUnionOfConceptsAxiom, owlapi.types.bundleStatements.ConceptTreeDisjunction)])
  (implicit ext: api.Extent)
  : ResolverResult
  = if (disjuncts.isEmpty)
    r2o.right
  else {
    val (dis0, ctd1) = disjuncts.head
    dis0 match {
      case ax0: api.AnonymousConceptUnionAxiom =>
        r2o.ops.addAnonymousConceptTaxonomyAxiom(b1, ctd1, tables.taggedTypes.localName(ax0.name))(r2o.omfStore) match {
          case \/-(ax1) =>
            val upd = r2o.copy(disjointUnionOfConceptAxioms = r2o.disjointUnionOfConceptAxioms + (ax0 -> ax1))
            val children = disjunctsForConceptTreeDisjunction(upd, ax0, ax1)
            convertConceptTreeDisjunctions(upd, b1, ax0, children ++ disjuncts.tail)
          case -\/(errors) =>
            -\/(errors)
        }
      case ax0: api.SpecificDisjointConceptAxiom =>
        for {
          l1 <- r2o.lookupConcept(ax0.disjointLeaf)
          ax1 <- r2o.ops.addSpecificDisjointConceptAxiom(b1, ctd1, l1)(r2o.omfStore)
        } yield r2o.copy(disjointUnionOfConceptAxioms = r2o.disjointUnionOfConceptAxioms + (ax0 -> ax1))
    }
  }

  // ChainRules

  private def convertChainRules(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.terminologyBoxOfTerminologyBoxStatement.foldLeft(acc)(convertChainRule(ext))
  }

  private def convertChainRule(implicit ext: api.Extent)
  : (ResolverResult, (api.TerminologyBoxStatement, api.TerminologyBox)) => ResolverResult
  = {
    case (acc, (ax0: api.ChainRule, t0: api.TerminologyGraph)) =>
      for {
        r2o <- acc
        t1 <- r2o.getGbox(t0)
        h1 <- r2o.lookupUnreifiedRelationship(ax0.head)
        ax1 <- r2o.ops.addChainRule(t1, h1, tables.taggedTypes.localName(ax0.name))(r2o.omfStore)
        _ <- ext.lookupFirstSegment(ax0) match {
          case Some(s0) =>
            convertRuleBodySegment(r2o.copy(chainRules = r2o.chainRules + (ax0 -> ax1)), ext, s0, t1, Some(ax1), None)
          case None =>
            Set[java.lang.Throwable](new IllegalArgumentException(
              s"OMLResolver2Ontology.convertChainRule: Missing first segment for rule: $ax0")).left
        }
        _ <- r2o.ops.makeChainRule(t1, ax1)(r2o.omfStore)
      } yield r2o
    case (acc, _) =>
      acc
  }

  @scala.annotation.tailrec
  private def convertRuleBodySegment
  (r2o: OMLResolver2Ontology,
   ext: api.Extent,
   s0: api.RuleBodySegment,
   t1: owlapi.types.terminologies.MutableTerminologyGraph,
   chainRule: Option[owlapi.types.terms.ChainRule],
   previousSegment: Option[owlapi.types.terms.RuleBodySegment])
  : ResolverResult
  = ext.predicate.get(s0) match {
    case Some(p0) =>
      r2o.ops.addRuleBodySegment(t1, chainRule, previousSegment)(r2o.omfStore) match {
        case \/-(s1) =>
          val conv
          : Throwables \/ (OMLResolver2Ontology, owlapi.types.terms.SegmentPredicate)
          = (p0.predicate,
           p0.reifiedRelationshipSource,
           p0.reifiedRelationshipInverseSource,
           p0.reifiedRelationshipTarget,
           p0.reifiedRelationshipInverseTarget,
           p0.unreifiedRelationshipInverse) match {

            case (Some(a0: api.Aspect), _, _, _, _, _) =>
              r2o.lookupAspect(a0)(ext).flatMap { a1 =>
                r2o.ops.addSegmentPredicate(t1, s1, predicate=Some(a1))(r2o.omfStore).map { p1 =>
                  (r2o.copy(segmentPredicates = r2o.segmentPredicates + (p0 -> p1)), p1)
                }
              }

            case (Some(c0: api.Concept), _, _, _, _, _) =>
              r2o.lookupConcept(c0)(ext).flatMap { c1 =>
                r2o.ops.addSegmentPredicate(t1, s1, predicate=Some(c1))(r2o.omfStore).map { p1 =>
                  (r2o.copy(segmentPredicates = r2o.segmentPredicates + (p0 -> p1)), p1)
                }
              }

            case (Some(rr0: api.ReifiedRelationshipRestriction), _, _, _, _, _) =>
              r2o.lookupReifiedRelationshipRestriction(rr0)(ext).flatMap { rr1 =>
                r2o.ops.addSegmentPredicate(t1, s1, predicate=Some(rr1))(r2o.omfStore).map { p1 =>
                  (r2o.copy(segmentPredicates = r2o.segmentPredicates + (p0 -> p1)), p1)
                }
              }

            case (Some(rr0: api.ReifiedRelationship), _, _, _, _, _) =>
              r2o.lookupReifiedRelationship(rr0)(ext).flatMap { rr1 =>
                r2o.ops.addSegmentPredicate(t1, s1, predicate=Some(rr1))(r2o.omfStore).map { p1 =>
                  (r2o.copy(segmentPredicates = r2o.segmentPredicates + (p0 -> p1)), p1)
                }
              }

            case (Some(fwd0: api.ForwardProperty), _, _, _, _, _) =>
              r2o.lookupReifiedRelationship(fwd0.reifiedRelationship)(ext).flatMap { rr1 =>
                r2o.ops.addSegmentPredicate(t1, s1, predicate=Some(rr1.forwardProperty))(r2o.omfStore).map { p1 =>
                  (r2o.copy(segmentPredicates = r2o.segmentPredicates + (p0 -> p1)), p1)
                }
              }

            case (Some(inv0: api.InverseProperty), _, _, _, _, _) =>
              r2o.lookupReifiedRelationship(inv0.reifiedRelationship)(ext).flatMap { rr1 =>
                rr1.inverseProperty match {
                  case Some(inv) =>
                    r2o.ops.addSegmentPredicate(t1, s1, predicate = Some(inv))(r2o.omfStore).map { p1 =>
                      (r2o.copy(segmentPredicates = r2o.segmentPredicates + (p0 -> p1)), p1)
                    }
                  case _ =>
                    -\/(Set[java.lang.Throwable](new IllegalArgumentException("")
                    ))
                }
              }

            case (Some(ur0: api.UnreifiedRelationship), _, _, _, _, _) =>
              r2o.lookupUnreifiedRelationship(ur0)(ext).flatMap { ur1 =>
                r2o.ops.addSegmentPredicate(t1, s1, predicate=Some(ur1))(r2o.omfStore).map { p1 =>
                  (r2o.copy(segmentPredicates = r2o.segmentPredicates + (p0 -> p1)), p1)
                }
              }

            case (None, Some(rr0), _, _, _, _) =>
              r2o.lookupReifiedRelationship(rr0)(ext).flatMap { rr1 =>
                r2o.ops.addSegmentPredicate(t1, s1, reifiedRelationshipSource=Some(rr1))(r2o.omfStore).map { p1 =>
                  (r2o.copy(segmentPredicates = r2o.segmentPredicates + (p0 -> p1)), p1)
                }
              }

            case (None, _, Some(rr0), _, _, _) =>
              r2o.lookupReifiedRelationship(rr0)(ext).flatMap { rr1 =>
                r2o.ops.addSegmentPredicate(t1, s1, reifiedRelationshipInverseSource=Some(rr1))(r2o.omfStore).map { p1 =>
                  (r2o.copy(segmentPredicates = r2o.segmentPredicates + (p0 -> p1)), p1)
                }
              }

            case (None, _, _,Some(rr0), _, _) =>
              r2o.lookupReifiedRelationship(rr0)(ext).flatMap { rr1 =>
                r2o.ops.addSegmentPredicate(t1, s1, reifiedRelationshipTarget=Some(rr1))(r2o.omfStore).map { p1 =>
                  (r2o.copy(segmentPredicates = r2o.segmentPredicates + (p0 -> p1)), p1)
                }
              }

            case (None, _, _, _, Some(rr0), _) =>
              r2o.lookupReifiedRelationship(rr0)(ext).flatMap { rr1 =>
                r2o.ops.addSegmentPredicate(t1, s1, reifiedRelationshipInverseTarget=Some(rr1))(r2o.omfStore).map { p1 =>
                  (r2o.copy(segmentPredicates = r2o.segmentPredicates + (p0 -> p1)), p1)
                }
              }

            case (None, _, _, _, _, Some(ur0)) =>
              r2o.lookupUnreifiedRelationship(ur0)(ext).flatMap { ur1 =>
                r2o.ops.addSegmentPredicate(t1, s1, unreifiedRelationshipInverse=Some(ur1))(r2o.omfStore).map { p1 =>
                  (r2o.copy(segmentPredicates = r2o.segmentPredicates + (p0 -> p1)), p1)
                }
              }

            case (pred, source, isource, target, itarget, uinv) =>
              Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
                s"OWLResolver2Ontology.convertRuleBodySegment(ruleBodySegment=$s0) unresolved:\n" +
                  pred.fold[String]("- no predicate"){ x =>
                    s"- predicate: ${x.term().abbrevIRI()(ext)}"
                  } +
                  source.fold[String]("- no reifiedRelationshipSource"){ x =>
                    s"- reifiedRelationshipSource: ${x.abbrevIRI()(ext)}"
                  } +
                  isource.fold[String]("- no reifiedRelationshipInverseSource"){ x =>
                    s"- reifiedRelationshipInverseSource: ${x.abbrevIRI()(ext)}"
                  } +
                  target.fold[String]("- no reifiedRelationshipTarget"){ x =>
                    s"- reifiedRelationshipTarget: ${x.abbrevIRI()(ext)}"
                  } +
                  itarget.fold[String]("- no reifiedRelationshipInverseTarget"){ x =>
                    s"- reifiedRelationshipInverseTarget: ${x.abbrevIRI()(ext)}"
                  } +
                  uinv.fold[String]("- no unreifiedRelationshipInverse"){ x =>
                    s"- unreifiedRelationshipInverse: ${x.abbrevIRI()(ext)}"
                  }
              )).left
          }
          conv match {
            case \/-((updated, p1)) =>
              val next = updated.copy(ruleBodySegments = updated.ruleBodySegments + (s0 -> s1))
              ext.lookupNextSegment(s0) match {
                case Some(n0) =>
                  convertRuleBodySegment(next, ext, n0, t1, None, Some(s1))
                case None =>
                  next.right
              }
            case -\/(errors) =>
              -\/(errors)
          }
        case -\/(errors) =>
          -\/(errors)
      }
    case None =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.convertRuleBodySegment: Missing predicate for segment: $s0")).left
  }

  // ConceptualEntityInstances

  private def convertConceptInstances(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.descriptionBoxOfConceptInstance.foldLeft(acc)(convertConceptInstances(ext))
  }

  private def convertConceptInstances(implicit ext: api.Extent)
  : (ResolverResult, (api.ConceptInstance, api.DescriptionBox)) => ResolverResult
  = {
    case (acc, (ax0: api.ConceptInstance, d0: api.DescriptionBox)) =>
      for {
        r2o <- acc
        d1 <- r2o.getDbox(d0)
        c1 <- r2o.lookupConcept(ax0.singletonConceptClassifier)
        ax1 <- r2o.ops.addConceptInstance(d1, c1, tables.taggedTypes.localName(ax0.name))(r2o.omfStore)
      } yield r2o.copy(conceptualEntitySingletonInstances = r2o.conceptualEntitySingletonInstances + (ax0 -> ax1))
  }

  private def convertReifiedRelationshipInstances(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.descriptionBoxOfReifiedRelationshipInstance.foldLeft(acc)(convertReifiedRelationshipInstances(ext))
  }

  private def convertReifiedRelationshipInstances(implicit ext: api.Extent)
  : (ResolverResult, (api.ReifiedRelationshipInstance, api.DescriptionBox)) => ResolverResult
  = {
    case (acc, (ax0: api.ReifiedRelationshipInstance, d0: api.DescriptionBox)) =>
      for {
        r2o <- acc
        d1 <- r2o.getDbox(d0)
        rr1 <- r2o.lookupConceptualRelationship(ax0.singletonConceptualRelationshipClassifier)
        ax1 <- r2o.ops.addReifiedRelationshipInstance(d1, rr1, tables.taggedTypes.localName(ax0.name))(r2o.omfStore)
      } yield r2o.copy(conceptualEntitySingletonInstances = r2o.conceptualEntitySingletonInstances + (ax0 -> ax1))
  }

  private def convertReifiedRelationshipInstanceDomains(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.descriptionBoxOfReifiedRelationshipInstanceDomain.foldLeft(acc)(convertReifiedRelationshipInstanceDomains(ext))
  }

  private def convertReifiedRelationshipInstanceDomains(implicit ext: api.Extent)
  : (ResolverResult, (api.ReifiedRelationshipInstanceDomain, api.DescriptionBox)) => ResolverResult
  = {
    case (acc, (ax0: api.ReifiedRelationshipInstanceDomain, d0: api.DescriptionBox)) =>
      for {
        r2o <- acc
        d1 <- r2o.getDbox(d0)
        s1 <- r2o.lookupConceptualEntityInstance(ax0.domain)
        rr1 <- r2o.lookupReifiedRelationshipInstance(ax0.reifiedRelationshipInstance)
        ax1 <- r2o.ops.addReifiedRelationshipInstanceDomain(d1, rr1, s1)(r2o.omfStore)
      } yield r2o.copy(reifiedRelationshipInstanceDomains = r2o.reifiedRelationshipInstanceDomains + (ax0 -> ax1))
  }

  private def convertReifiedRelationshipInstanceRanges(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.descriptionBoxOfReifiedRelationshipInstanceRange.foldLeft(acc)(convertReifiedRelationshipInstanceRanges(ext))
  }

  private def convertReifiedRelationshipInstanceRanges(implicit ext: api.Extent)
  : (ResolverResult, (api.ReifiedRelationshipInstanceRange, api.DescriptionBox)) => ResolverResult
  = {
    case (acc, (ax0: api.ReifiedRelationshipInstanceRange, d0: api.DescriptionBox)) =>
      for {
        r2o <- acc
        d1 <- r2o.getDbox(d0)
        s1 <- r2o.lookupConceptualEntityInstance(ax0.range)
        rr1 <- r2o.lookupReifiedRelationshipInstance(ax0.reifiedRelationshipInstance)
        ax1 <- r2o.ops.addReifiedRelationshipInstanceRange(d1, rr1, s1)(r2o.omfStore)
      } yield r2o.copy(reifiedRelationshipInstanceRanges = r2o.reifiedRelationshipInstanceRanges + (ax0 -> ax1))
  }

  private def convertUnreifiedReifiedRelationshipInstanceTuples(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.descriptionBoxOfUnreifiedRelationshipInstanceTuple.foldLeft(acc)(convertUnreifiedReifiedRelationshipInstanceTuples(ext))
  }

  private def convertUnreifiedReifiedRelationshipInstanceTuples(implicit ext: api.Extent)
  : (ResolverResult, (api.UnreifiedRelationshipInstanceTuple, api.DescriptionBox)) => ResolverResult
  = {
    case (acc, (ax0: api.UnreifiedRelationshipInstanceTuple, d0: api.DescriptionBox)) =>
      for {
        r2o <- acc
        d1 <- r2o.getDbox(d0)
        s1 <- r2o.lookupConceptualEntityInstance(ax0.domain)
        t1 <- r2o.lookupConceptualEntityInstance(ax0.range)
        ur1 <- r2o.lookupUnreifiedRelationship(ax0.unreifiedRelationship)
        ax1 <- r2o.ops.addUnreifiedRelationshipInstanceTuple(d1, ur1, s1, t1)(r2o.omfStore)
      } yield r2o.copy(unreifiedRelationshipInstanceTuples = r2o.unreifiedRelationshipInstanceTuples + (ax0 -> ax1))
  }

  // Data Property Values of ConceptualEntityInstances

  private def convertSingletonInstanceScalarDataPropertyValues(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.descriptionBoxOfSingletonInstanceScalarDataPropertyValue.foldLeft(acc)(convertSingletonInstanceScalarDataPropertyValues(ext))
  }

  private def convertSingletonInstanceScalarDataPropertyValues(implicit ext: api.Extent)
  : (ResolverResult, (api.SingletonInstanceScalarDataPropertyValue, api.DescriptionBox)) => ResolverResult
  = {
    case (acc, (ax0: api.SingletonInstanceScalarDataPropertyValue, d0: api.DescriptionBox)) =>
      for {
        r2o <- acc
        d1 <- r2o.getDbox(d0)
        s1 <- r2o.lookupConceptualEntityInstance(ax0.singletonInstance)
        dp1 <- r2o.lookupEntityScalarDataProperty(ax0.scalarDataProperty)
        vt1 <- ax0.valueType match {
          case Some(vt0) =>
            r2o.lookupDataRange(vt0).map(Option.apply)
          case None =>
            None.right
        }
        ax1 <- r2o.ops.addSingletonInstanceScalarDataPropertyValue(d1, s1, dp1, ax0.scalarPropertyValue, vt1)(r2o.omfStore)
      } yield r2o.copy(singletonScalarDataPropertyValues = r2o.singletonScalarDataPropertyValues + (ax0 -> ax1))
  }

  private def convertSingletonInstanceStructuredDataPropertyValues(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.descriptionBoxOfSingletonInstanceStructuredDataPropertyValue.foldLeft(acc)(convertSingletonInstanceStructuredDataPropertyValues(ext))
  }

  private def convertSingletonInstanceStructuredDataPropertyValues(implicit ext: api.Extent)
  : (ResolverResult, (api.SingletonInstanceStructuredDataPropertyValue, api.DescriptionBox)) => ResolverResult
  = {
    case (acc, (ax0: api.SingletonInstanceStructuredDataPropertyValue, d0: api.DescriptionBox)) =>
      for {
        r2o <- acc
        d1 <- r2o.getDbox(d0)
        s1 <- r2o.lookupConceptualEntityInstance(ax0.singletonInstance)
        dp1 <- r2o.lookupEntityStructuredDataProperty(ax0.structuredDataProperty)
        ax1 <- r2o.ops.addSingletonInstanceStructuredDataPropertyValue(d1, s1, dp1)(r2o.omfStore)
      } yield r2o.copy(singletonStructuredDataPropertyValues = r2o.singletonStructuredDataPropertyValues + (ax0 -> ax1))
  }

  private def convertStructuredPropertyTuples(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.structuredPropertyTuples.foldLeft(acc)(convertStructuredPropertyTuples(ext))
  }

  private def convertStructuredPropertyTuples(implicit ext: api.Extent)
  : (ResolverResult, (api.SingletonInstanceStructuredDataPropertyContext, Set[api.StructuredDataPropertyTuple])) => ResolverResult
  = {
    case (acc, (c0, ts0)) =>
      for {
        r2o1 <- acc
        c1 <- r2o1.lookupSingletonInstanceStructuredDataPropertyContext(c0)
        r2oN <- ts0.foldLeft(acc)(convertStructuredDataPropertyValue(c0, c1)(ext))
      } yield r2oN
  }

  private def convertStructuredDataPropertyValue
  (c0: api.SingletonInstanceStructuredDataPropertyContext,
   c1: owlapi.descriptions.SingletonInstanceStructuredDataPropertyContext)
  (implicit ext: api.Extent)
  : (ResolverResult, api.StructuredDataPropertyTuple) => ResolverResult
  = {
    case (acc, t0) =>
      // TODO
      acc
  }

  private def convertScalarDataPropertyValues(r2o: OMLResolver2Ontology)
  : ResolverResult
  = r2o.extents.foldLeft(r2o.right[Throwables]) {
    case (acc, ext) =>
      ext.scalarDataPropertyValues.foldLeft(acc)(convertScalarDataPropertyValues(ext))
  }

  private def convertScalarDataPropertyValues(implicit ext: api.Extent)
  : (ResolverResult, (api.SingletonInstanceStructuredDataPropertyContext, Set[api.ScalarDataPropertyValue])) => ResolverResult
  = {
    case (acc, (c0, vs0)) =>
      for {
        r2o1 <- acc
        c1 <- r2o1.lookupSingletonInstanceStructuredDataPropertyContext(c0)
        r2oN <- vs0.foldLeft(acc)(convertScalarDataPropertyValue(c0, c1)(ext))
      } yield r2oN
  }

  private def convertScalarDataPropertyValue
  (c0: api.SingletonInstanceStructuredDataPropertyContext,
   c1: owlapi.descriptions.SingletonInstanceStructuredDataPropertyContext)
  (implicit ext: api.Extent)
  : (ResolverResult, api.ScalarDataPropertyValue) => ResolverResult
  = {
    case (acc, v0) =>
      // TODO
      acc
  }
}

case class OMLResolver2Ontology
(om: OntologyMapping,
 omfStore: owlapi.OWLAPIOMFGraphStore,
 extents: Set[api.Extent] = Set.empty,

 // Modules
 modules
 : Set[api.Module]
 = Set.empty,

 gs
 : Map[api.TerminologyGraph, owlapiterminologies.MutableTerminologyGraph]
 = Map.empty,

 bs
 : Map[api.Bundle, owlapiterminologies.MutableBundle]
 = Map.empty,

 ds
 : Map[api.DescriptionBox, owlapidescriptions.MutableDescriptionBox]
 = Map.empty,

 // AnnotationProperties
 aps
 : Map[api.AnnotationProperty, tables.AnnotationProperty]
 = Map.empty,

 // ModuleEdges
 edges
 : Map[api.ModuleEdge, owlapi.common.ModuleEdge]
 = Map.empty,

 aspects
 : Map[api.Aspect, owlapi.types.terms.Aspect]
 = Map.empty,

 concepts
 : Map[api.Concept, owlapi.types.terms.Concept]
 = Map.empty,

 reifiedRelationshipRestrictions
 : Map[api.ReifiedRelationshipRestriction, owlapi.types.terms.ReifiedRelationshipRestriction]
 = Map.empty,

 reifiedRelationships
 : Map[api.ReifiedRelationship, owlapi.types.terms.ReifiedRelationship]
 = Map.empty,

 forwardProperties
 : Map[api.ForwardProperty, owlapi.types.terms.ForwardProperty]
 = Map.empty,

 inverseProperties
 : Map[api.InverseProperty, owlapi.types.terms.InverseProperty]
 = Map.empty,

 unreifiedRelationships
 : Map[api.UnreifiedRelationship, owlapi.types.terms.UnreifiedRelationship]
 = Map.empty,

 dataRanges
 : Map[api.DataRange, owlapi.types.terms.DataRange]
 = Map.empty,

 structures
 : Map[api.Structure, owlapi.types.terms.Structure]
 = Map.empty,

 entityScalarDataProperties
 : Map[api.EntityScalarDataProperty, owlapi.types.terms.EntityScalarDataProperty]
 = Map.empty,

 entityStructuredDataProperties
 : Map[api.EntityStructuredDataProperty, owlapi.types.terms.EntityStructuredDataProperty]
 = Map.empty,

 scalarDataProperties
 : Map[api.ScalarDataProperty, owlapi.types.terms.ScalarDataProperty]
 = Map.empty,

 structuredDataProperties
 : Map[api.StructuredDataProperty, owlapi.types.terms.StructuredDataProperty]
 = Map.empty,

 termAxioms
 : Map[api.TermAxiom, owlapi.types.termAxioms.TermAxiom]
 = Map.empty,

 conceptTreeDisjunctions
 : Map[api.ConceptTreeDisjunction, owlapi.types.bundleStatements.ConceptTreeDisjunction]
 = Map.empty,

 disjointUnionOfConceptAxioms
 : Map[api.DisjointUnionOfConceptsAxiom, owlapi.types.bundleStatements.DisjointUnionOfConceptsAxiom]
 = Map.empty,

 chainRules
 : Map[api.ChainRule, owlapi.types.terms.ChainRule]
 = Map.empty,

 ruleBodySegments
 : Map[api.RuleBodySegment, owlapi.types.terms.RuleBodySegment]
 = Map.empty,

 segmentPredicates
 : Map[api.SegmentPredicate, owlapi.types.terms.SegmentPredicate]
 = Map.empty,

 conceptualEntitySingletonInstances
 : Map[api.ConceptualEntitySingletonInstance, owlapi.descriptions.ConceptualEntitySingletonInstance]
 = Map.empty,

 reifiedRelationshipInstanceDomains
 : Map[api.ReifiedRelationshipInstanceDomain, owlapi.descriptions.ReifiedRelationshipInstanceDomain]
 = Map.empty,

 reifiedRelationshipInstanceRanges
 : Map[api.ReifiedRelationshipInstanceRange, owlapi.descriptions.ReifiedRelationshipInstanceRange]
 = Map.empty,

 unreifiedRelationshipInstanceTuples
 : Map[api.UnreifiedRelationshipInstanceTuple, owlapi.descriptions.UnreifiedRelationshipInstanceTuple]
 = Map.empty,

 singletonScalarDataPropertyValues
 : Map[api.SingletonInstanceScalarDataPropertyValue, owlapi.descriptions.SingletonInstanceScalarDataPropertyValue]
 = Map.empty,

 singletonStructuredDataPropertyValues
 : Map[api.SingletonInstanceStructuredDataPropertyValue, owlapi.descriptions.SingletonInstanceStructuredDataPropertyValue]
 = Map.empty,

 structuredPropertyTuples
 : Map[api.StructuredDataPropertyTuple, owlapi.descriptions.StructuredDataPropertyTuple]
 = Map.empty,

 scalarDataPropertyValues
 : Map[api.ScalarDataPropertyValue, owlapi.descriptions.ScalarDataPropertyValue]
 = Map.empty,

 singletonInstanceStructuredDataPropertyContexts
 : Map[api.SingletonInstanceStructuredDataPropertyContext, owlapi.descriptions.SingletonInstanceStructuredDataPropertyContext]
 = Map.empty,

 restrictionStructuredDataPropertyTuples
 : Map[api.RestrictionStructuredDataPropertyTuple, owlapi.types.RestrictionStructuredDataPropertyTuple]
 = Map.empty,

 restrictionScalarDataPropertyValues
 : Map[api.RestrictionScalarDataPropertyValue, owlapi.types.RestrictionScalarDataPropertyValue]
 = Map.empty


) {
  def withBuiltIn
  (m0: api.Module, m1: OMLResolver2Ontology.MutableTboxOrDbox)
  (implicit ext: api.Extent)
  : core.OMFError.Throwables \/ OMLResolver2Ontology
  = (m0, m1) match {
    case (g0: api.TerminologyGraph, -\/(g1: owlapiterminologies.TerminologyGraph)) =>

      val r2oWithAPS
      : core.OMFError.Throwables \/ OMLResolver2Ontology
      = ext
        .annotationProperties
        .foldLeft[core.OMFError.Throwables \/ OMLResolver2Ontology](this.right) {
        case (acc1, (t0: api.TerminologyBox, aps0)) =>
          for {
            r2o <- acc1
            t1 <- r2o.getTbox(t0)
            next <- aps0.foldLeft(acc1) { case (acc2, ap0) =>
              for {
                prev <- acc2
                apUUID = ap0.uuid
                aps1 = t1.annotationProperties()
                f1 = aps1.find(_.uuid == apUUID.toString)
                updated <- f1 match {
                    case Some(ap1) =>
                      prev.copy(aps = prev.aps + (ap0 -> ap1)).right
                    case _ =>
                      Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
                        s"withBuiltIn: Failed to lookup annotation property: $ap0 from ${g1.iri}")).left
                  }
              } yield updated
            }
          } yield next
        case (acc, _) =>
          acc
      }

      g0
        .moduleElements()
        .selectByKindOf { case d0: api.DataRange => d0 }
        .foldLeft[core.OMFError.Throwables \/ OMLResolver2Ontology](r2oWithAPS) {
        case (\/-(r2o), d0) =>
          g1.lookupTerm(d0.iri().map(IRI.create), recursively=false)(this.omfStore) match {
            case Some(d1: owlapi.types.terms.DataRange) =>
              r2o.copy(dataRanges = r2o.dataRanges + (d0 -> d1)).right
            case _ =>
              Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
                s"withBuiltIn: Failed to lookup data range: ${d0.iri()} from ${g1.iri}")).left
          }
        case (acc, _) =>
          acc
      }
    case _ =>
      Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
        s"withBuiltIn: Invalid modules: m0=$m0, m1=$m1")).left
  }

  def getModule(m0: api.Module):  core.OMFError.Throwables \/ owlapi.common.MutableModule
  = m0 match {
    case t0: api.TerminologyBox =>
      getTbox(t0)
    case d0: api.DescriptionBox =>
      getDbox(d0)
  }

  def getTbox(m0: api.TerminologyBox): core.OMFError.Throwables \/ owlapiterminologies.MutableTerminologyBox
  = (m0 match {
    case mt: api.TerminologyGraph =>
      gs.get(mt)
    case mb: api.Bundle =>
      bs.get(mb)
  }) match {
    case Some(m1) =>
      m1.right
    case None =>
      Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
        s"OWLResolver2Ontology.getTbox(${m0.kind} ${m0.iri}) not found."
      )).left
  }

  def getGbox(m0: api.TerminologyBox): core.OMFError.Throwables \/ owlapiterminologies.MutableTerminologyGraph
  = (m0 match {
    case mt: api.TerminologyGraph =>
      gs.get(mt)
    case _ =>
      None
  }) match {
    case Some(m1) =>
      m1.right
    case None =>
      Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
        s"OWLResolver2Ontology.getGbox(${m0.kind} ${m0.iri}) not found."
      )).left
  }

  private val iri2mutableTbox
  : Map[tables.taggedTypes.IRI, owlapiterminologies.MutableTerminologyBox]
  = gs.map { case (g, mg) => g.iri -> mg } ++ bs.map { case (b, mb) => b.iri -> mb }

  def getTboxByIRI(iri: tables.taggedTypes.IRI): core.OMFError.Throwables \/ owlapiterminologies.MutableTerminologyBox
  = iri2mutableTbox.get(iri) match {
    case Some(mtbox) =>
      mtbox.right[core.OMFError.Throwables]
    case None =>
      Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
        s"OMLResolver2Ontology.getTbox(iri=$iri) Not found!"
      )).left
  }

  def lookupTbox(t0: api.TerminologyBox): Option[owlapiterminologies.MutableTerminologyBox]
  = t0 match {
    case mt: api.TerminologyGraph =>
      gs.get(mt)
    case mb: api.Bundle =>
      bs.get(mb)
  }

  def getBundle(b0: api.Bundle): core.OMFError.Throwables \/ owlapiterminologies.MutableBundle
  = bs.get(b0) match {
    case Some(b1) =>
      b1.right
    case None =>
      Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
        s"OWLResolver2Ontology.getBundle(${b0.kind} ${b0.iri}) not found."
      )).left
  }

  def getDbox(m0: api.DescriptionBox): core.OMFError.Throwables \/ owlapidescriptions.MutableDescriptionBox
  = this.ds.get(m0) match {
    case Some(m1) =>
      m1.right
    case None =>
      Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
        s"OWLResolver2Ontology.getDbox(${m0.kind} ${m0.iri}) not found."
      )).left
  }

  def getDboxByIRI(iri: tables.taggedTypes.IRI): core.OMFError.Throwables \/ owlapidescriptions.MutableDescriptionBox
  = ds.values
    .find { dbox =>
      dbox.iri.toString == iri
    } match {
    case Some(mdbox) =>
      mdbox.right[core.OMFError.Throwables]
    case None =>
      Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
        s"OMLResolver2Ontology.lookupDbox(iri=$iri) Not found!"
      )).left
  }

  def lookupAspect(a0: api.Aspect)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.types.terms.Aspect
  = aspects.get(a0) match {
    case Some(a1) =>
      a1.right
    case None =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.lookupAspect(a=${a0.iri()}) failed")).left
  }

  def lookupConcept(c0: api.Concept)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.types.terms.Concept
  = concepts.get(c0) match {
    case Some(c1) =>
      c1.right
    case None =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.lookupConcept(c=${c0.iri()}) failed")).left
  }

  def lookupConceptualRelationship(cr0: api.ConceptualRelationship)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.types.terms.ConceptualRelationship
  = cr0 match {
    case rr0: api.ReifiedRelationship =>
    reifiedRelationships.get(rr0) match {
      case Some(rr1) =>
        rr1.right
      case None =>
        Set[java.lang.Throwable](new IllegalArgumentException(
          s"OMLResolver2Ontology.lookupConceptualRelationship(rr=${rr0.iri()}) failed")).left
    }
    case rs0: api.ReifiedRelationshipRestriction =>
      reifiedRelationshipRestrictions.get(rs0) match {
        case Some(rs1) =>
          rs1.right
        case None =>
          Set[java.lang.Throwable](new IllegalArgumentException(
            s"OMLResolver2Ontology.lookupConceptualRelationship(rr=${rs0.iri()}) failed")).left
      }
  }

  def lookupReifiedRelationshipRestriction(rr0: api.ReifiedRelationshipRestriction)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.types.terms.ReifiedRelationshipRestriction
  = reifiedRelationshipRestrictions.get(rr0) match {
    case Some(rr1) =>
      rr1.right
    case None =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.lookupReifiedRelationshipRestriction(rr=${rr0.iri()}) failed")).left
  }

  def lookupReifiedRelationship(rr0: api.ReifiedRelationship)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.types.terms.ReifiedRelationship
  = reifiedRelationships.get(rr0) match {
    case Some(rr1) =>
      rr1.right
    case None =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.lookupReifiedRelationship(rr=${rr0.iri()}) failed")).left
  }

  def lookupUnreifiedRelationship(ur0: api.UnreifiedRelationship)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.types.terms.UnreifiedRelationship
  = unreifiedRelationships.get(ur0) match {
    case Some(ur1) =>
      ur1.right
    case None =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.lookupUnreifiedRelationship(ur=${ur0.iri()}) failed")).left
  }

  def lookupDataRange(d0: api.DataRange)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.types.terms.DataRange
  = dataRanges.get(d0) match {
    case Some(d1) =>
      d1.right
    case None =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.lookupDataRange(d=${d0.iri()}) failed")).left
  }

  def lookupStructure(s0: api.Structure)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.types.terms.Structure
  = structures.get(s0) match {
    case Some(s1) =>
      s1.right
    case None =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.lookupStructure(s=${s0.iri()}) failed")).left
  }

  def lookupScalarOneOfRestriction(d0: api.ScalarOneOfRestriction)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.types.terms.ScalarOneOfRestriction
  = dataRanges.get(d0) match {
    case Some(d1: owlapi.types.terms.ScalarOneOfRestriction) =>
      d1.right
    case _ =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.lookupScalarOneOfRestriction(d=${d0.iri()}) failed")).left
  }

  def lookupEntity(e0: api.Entity): Option[owlapi.types.terms.Entity]
  = e0 match {
    case a0: api.Aspect =>
      aspects.get(a0)
    case c0: api.Concept =>
      concepts.get(c0)
    case rs0: api.ReifiedRelationshipRestriction =>
      reifiedRelationshipRestrictions.get(rs0)
    case rr0: api.ReifiedRelationship =>
      reifiedRelationships.get(rr0)
  }

  def entityLookup(e0: api.Entity)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.types.terms.Entity
  = lookupEntity(e0) match {
    case Some(e1) =>
      e1.right
    case None =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.entityLookup(e=${e0.iri()}) failed")).left
  }

  def restrictableRelationshipLookup(e0: api.RestrictableRelationship)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.common.RestrictableRelationship
  = e0 match {
    case u0: api.UnreifiedRelationship =>
      unreifiedRelationships.get(u0) match {
        case Some(u1) =>
          u1.right
        case _ =>
          Set[java.lang.Throwable](new IllegalArgumentException(
            s"OMLResolver2Ontology.restrictableRelationshipLookup(unreifiedRelationship=${u0.iri()}) failed")).left
      }
    case f0: api.ForwardProperty =>
      forwardProperties.get(f0) match {
        case Some(f1) =>
          f1.right
        case _ =>
          Set[java.lang.Throwable](new IllegalArgumentException(
            s"OMLResolver2Ontology.restrictableRelationshipLookup(forwardProperty=${f0.iri()}) failed")).left
      }
    case f0: api.InverseProperty =>
      inverseProperties.get(f0) match {
        case Some(f1) =>
          f1.right
        case _ =>
          Set[java.lang.Throwable](new IllegalArgumentException(
            s"OMLResolver2Ontology.restrictableRelationshipLookup(inverseProperty=${f0.iri()}) failed")).left
      }
  }

  def entityRelationshipLookup(e0: api.EntityRelationship)(implicit ext: api.Extent)
  : core.OMFError.Throwables \/ owlapi.types.terms.EntityRelationship
  = e0 match {
    case rr0: api.ReifiedRelationship =>
      lookup(rr0, reifiedRelationships)
    case ur0: api.UnreifiedRelationship =>
      lookup(ur0, unreifiedRelationships)
  }

  def lookupEntityScalarDataProperty(d0: api.EntityScalarDataProperty)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.types.terms.EntityScalarDataProperty
  = entityScalarDataProperties.get(d0) match {
    case Some(d1: owlapi.types.terms.EntityScalarDataProperty) =>
      d1.right
    case _ =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.lookupEntityScalarDataProperty(d=${d0.iri()}) failed")).left
  }

  def lookupEntityStructuredDataProperty(d0: api.DataRelationshipToStructure)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.types.terms.EntityStructuredDataProperty
  = d0 match {
    case es0: api.EntityStructuredDataProperty =>
      entityStructuredDataProperties.get(es0) match {
        case Some(es1: owlapi.types.terms.EntityStructuredDataProperty) =>
          es1.right
        case _ =>
          Set[java.lang.Throwable](new IllegalArgumentException(
            s"OMLResolver2Ontology.lookupEntityStructuredDataProperty(d=${d0.iri()}) failed")).left
      }
    case _ =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.lookupEntityStructuredDataProperty(d=${d0.iri()}) property is not an EntityStructuredDataProperty")).left
  }

  def lookupDataRelationshipToStructure(d0: api.DataRelationshipToStructure)(implicit ext: api.Extent)
  : core.OMFError.Throwables \/ owlapi.types.terms.DataRelationshipToStructure
  = d0 match {
    case es0: api.EntityStructuredDataProperty =>
      entityStructuredDataProperties.get(es0) match {
        case Some(es1: owlapi.types.terms.EntityStructuredDataProperty) =>
          es1.right
        case _ =>
          Set[java.lang.Throwable](new IllegalArgumentException(
            s"OMLResolver2Ontology.lookupDataRelationshipToStructure(d=${d0.iri()}) failed")).left
      }
    case es0: api.StructuredDataProperty =>
      structuredDataProperties.get(es0) match {
        case Some(es1: owlapi.types.terms.StructuredDataProperty) =>
          es1.right
        case _ =>
          Set[java.lang.Throwable](new IllegalArgumentException(
            s"OMLResolver2Ontology.lookupDataRelationshipToStructure(d=${d0.iri()}) failed")).left
      }
  }

  def lookupDataRelationshipToScalar(d0: api.DataRelationshipToScalar)(implicit ext: api.Extent)
  : core.OMFError.Throwables \/ owlapi.types.terms.DataRelationshipToScalar
  = d0 match {
    case es0: api.EntityScalarDataProperty =>
      entityScalarDataProperties.get(es0) match {
        case Some(es1: owlapi.types.terms.EntityScalarDataProperty) =>
          es1.right
        case _ =>
          Set[java.lang.Throwable](new IllegalArgumentException(
            s"OMLResolver2Ontology.lookupDataRelationshipToScalar(d=${d0.iri()}) failed")).left
      }
    case es0: api.ScalarDataProperty =>
      scalarDataProperties.get(es0) match {
        case Some(es1: owlapi.types.terms.ScalarDataProperty) =>
          es1.right
        case _ =>
          Set[java.lang.Throwable](new IllegalArgumentException(
            s"OMLResolver2Ontology.lookupDataRelationshipToScalar(d=${d0.iri()}) failed")).left
      }
  }

  def lookupScalarDataProperty(d0: api.ScalarDataProperty)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.types.terms.ScalarDataProperty
  = scalarDataProperties.get(d0) match {
    case Some(d1: owlapi.types.terms.ScalarDataProperty) =>
      d1.right
    case _ =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.lookupScalarDataProperty(d=${d0.iri()}) failed")).left
  }

  def lookupStructuredDataProperty(d0: api.StructuredDataProperty)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.types.terms.StructuredDataProperty
  = structuredDataProperties.get(d0) match {
    case Some(d1: owlapi.types.terms.StructuredDataProperty) =>
      d1.right
    case _ =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.lookupStructuredDataProperty(d=${d0.iri()}) failed")).left
  }

  def lookupConceptualEntityInstance(s0: api.ConceptualEntitySingletonInstance)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.descriptions.ConceptualEntitySingletonInstance
  = conceptualEntitySingletonInstances.get(s0) match {
    case Some(s1) =>
      s1.right
    case None =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.lookupConceptualEntityInstance(s=${s0.iri()}) failed")).left
  }

  def lookupReifiedRelationshipInstance(rr0: api.ReifiedRelationshipInstance)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.descriptions.ReifiedRelationshipInstance
  = conceptualEntitySingletonInstances.get(rr0) match {
    case Some(rr1: owlapi.descriptions.ReifiedRelationshipInstance) =>
      rr1.right
    case _ =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.lookupReifiedRelationshipInstance(rr=${rr0.iri()}) failed")).left
  }

  def lookupSingletonInstanceStructuredDataPropertyContext
  (t0: api.SingletonInstanceStructuredDataPropertyContext)
  (implicit ext: api.Extent)
  : core.OMFError.Throwables \/ owlapi.descriptions.SingletonInstanceStructuredDataPropertyContext
  = singletonInstanceStructuredDataPropertyContexts.get(t0) match {
    case Some(t1: owlapi.descriptions.SingletonInstanceStructuredDataPropertyContext) =>
      t1.right
    case _ =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.lookupSingletonInstanceStructuredDataPropertyContext(rr=${t0.uuid}) failed")).left
  }

  def lookup[U <: api.LogicalElement, V <: owlapi.common.LogicalElement]
  (u: U, uv: Map[U, V])
  (implicit ext: api.Extent)
  : core.OMFError.Throwables \/ V
  = uv.get(u) match {
    case Some(u) =>
      u.right[core.OMFError.Throwables]
    case _ =>
      Set[java.lang.Throwable](new IllegalArgumentException(
        s"OMLResolver2Ontology.lookup failed for: $u")).left
  }

  def lookupModuleElement(me: api.ModuleElement)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.common.ModuleElement
  = me match {
    case x: api.Aspect =>
      lookupAspect(x)
    case x: api.Concept =>
      lookupConcept(x)
    case x: api.ReifiedRelationshipRestriction =>
      lookupReifiedRelationshipRestriction(x)
    case x: api.ReifiedRelationship =>
      lookupReifiedRelationship(x)
    case x: api.UnreifiedRelationship =>
      lookupUnreifiedRelationship(x)
    case x: api.DataRange =>
      lookupDataRange(x)
    case x: api.Structure =>
      lookupStructure(x)
    case x: api.TermAxiom =>
      lookup(x, termAxioms)
    case x: api.DataRelationshipToStructure =>
      lookupDataRelationshipToStructure(x)
    case x: api.DataRelationshipToScalar =>
      lookupDataRelationshipToScalar(x)
    case x: api.ConceptualEntitySingletonInstance =>
      lookupConceptualEntityInstance(x)
    case x: api.ReifiedRelationshipInstanceDomain =>
      lookup(x, reifiedRelationshipInstanceDomains)
    case x: api.ReifiedRelationshipInstanceRange =>
      lookup(x, reifiedRelationshipInstanceRanges)
    case x: api.UnreifiedRelationshipInstanceTuple =>
      lookup(x, unreifiedRelationshipInstanceTuples)
    case x: api.SingletonInstanceScalarDataPropertyValue =>
      lookup(x, singletonScalarDataPropertyValues)
    case x: api.SingletonInstanceStructuredDataPropertyValue =>
      lookup(x, singletonStructuredDataPropertyValues)
    case x: api.RestrictionStructuredDataPropertyTuple =>
      lookup(x, restrictionStructuredDataPropertyTuples)
    case x: api.ChainRule =>
      lookup(x, chainRules)
  }

  def lookupOtherElement(e: api.LogicalElement)(implicit ext: api.Extent): core.OMFError.Throwables \/ owlapi.common.LogicalElement
  = e match {
    case x: api.ConceptTreeDisjunction =>
      lookup(x, conceptTreeDisjunctions)
    case x: api.DisjointUnionOfConceptsAxiom =>
      lookup(x, disjointUnionOfConceptAxioms)
    case x: api.RuleBodySegment =>
      lookup(x, ruleBodySegments)
    case x: api.SegmentPredicate =>
      lookup(x, segmentPredicates)
    case x: api.RestrictionScalarDataPropertyValue =>
      lookup(x, restrictionScalarDataPropertyValues)
    case x: api.StructuredDataPropertyTuple =>
      lookup(x, structuredPropertyTuples)
    case x: api.ScalarDataPropertyValue =>
      lookup(x, scalarDataPropertyValues)
  }

  val ops: owlapi.OWLAPIOMFOps = omfStore.ops

  private def convertTerminologyGraph(uuid: UUID, g0: api.TerminologyGraph)
  : core.OMFError.Throwables \/ (OMLResolver2Ontology, api.Module, OMLResolver2Ontology.MutableTboxOrDbox, IRI)
  = {
    val i = IRI.create(g0.iri)
    om.lookupMutableTerminologyGraph(i)(this.ops) match {
      case Some(g1) =>
        \/-((
          this.copy(modules = this.modules + g0, gs = this.gs + (g0 -> g1)),
          g0,
          g1.left,
          i))
      case None =>
        val k = OMLResolver2Ontology.convertTerminologyKind(g0.kind)
        for {
          g1_om <- this.om.drc.lookupBuiltInModule(i)(this.ops) match {
            case Some(mbox: owlapiterminologies.MutableTerminologyGraph) =>
              (mbox -> om).right[core.OMFError.Throwables]
            case _ =>
              this.ops.makeTerminologyGraph(i, k)(this.omfStore).flatMap { m =>
                om.addMutableModule(m)(this.ops).map { omm =>
                  m -> omm
                }
              }
          }
          (g1, omg) = g1_om
          _ <- if (g1.uuid == uuid && g0.uuid == uuid)
            ().right[core.OMFError.Throwables]
          else
            Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
              s"convertTerminologyBox(g0=${g0.iri}) UUID mismatch\n g0.uuid=${g0.uuid}\n g1.uuid=${g1.uuid}"
            )).left
          that = copy(
            om = omg,
            modules = this.modules + g0,
            gs = this.gs + (g0 -> g1))
        } yield (that, g0, g1.left, i)
    }
  }

  private def convertBundle(uuid: UUID, b0: api.Bundle)
  : core.OMFError.Throwables \/ (OMLResolver2Ontology, api.Module, OMLResolver2Ontology.MutableTboxOrDbox, IRI)
  = {
    val i = IRI.create(b0.iri)
    om.lookupMutableBundle(i)(this.ops) match {
      case Some(b1) =>
        \/-((
          this.copy(modules = this.modules + b0, bs = this.bs + (b0 -> b1)),
          b0,
          b1.left,
          i))
      case None =>
        val k = OMLResolver2Ontology.convertTerminologyKind(b0.kind)
        for {
          b1 <- this.ops.makeBundle(i, k)(this.omfStore)
          _ <- if (b1.uuid == uuid && b0.uuid == uuid)
            ().right[core.OMFError.Throwables]
          else
            Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
              s"convertBundle(b0=${b0.iri}) UUID mismatch\n b0.uuid=${b0.uuid}\n b1.uuid=${b1.uuid}"
            )).left
          omb <- om.addMutableModule(b1)(this.ops)
          that = copy(
            om = omb,
            modules = this.modules + b0,
            bs = this.bs + (b0 -> b1))
        } yield (that, b0, b1.left, i)
    }
  }

  private def convertDescriptionBox(uuid: UUID, d0: api.DescriptionBox)
  : core.OMFError.Throwables \/ (OMLResolver2Ontology, api.Module, OMLResolver2Ontology.MutableTboxOrDbox, IRI)
  = {
    val i = IRI.create(d0.iri)
    om.lookupMutableDescriptionBox(i)(this.ops) match {
      case Some(d1) =>
        \/-((
          this.copy(modules = this.modules + d0, ds = this.ds + (d0 -> d1)),
          d0,
          d1.right,
          i))
      case None =>
        val k = OMLResolver2Ontology.convertDescriptionKind(d0.kind)
        for {
          d1 <- this.ops.makeDescriptionBox(i, k)(this.omfStore)
          _ <- if (d1.uuid == uuid && d0.uuid == uuid)
            ().right[core.OMFError.Throwables]
          else
            Set[java.lang.Throwable](new java.lang.IllegalArgumentException(
              s"convertDescriptionBox(d0=${d0.iri}) UUID mismatch\n d0.uuid=${d0.uuid}\n d1.uuid=${d1.uuid}"
            )).left
          omd <- om.addMutableModule(d1)(this.ops)
          that = copy(
            om = omd,
            modules = this.modules + d0,
            ds = this.ds + (d0 -> d1))
        } yield (that, d0, d1.right, i)
    }
  }

}

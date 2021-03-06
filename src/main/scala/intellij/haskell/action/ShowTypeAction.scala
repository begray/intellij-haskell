/*
 * Copyright 2014-2017 Rik van der Kleij
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

package intellij.haskell.action

import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.{Editor, SelectionModel}
import com.intellij.openapi.project.Project
import com.intellij.psi.{PsiElement, PsiFile}
import intellij.haskell.editor.HaskellCompletionContributor
import intellij.haskell.external.component.{HaskellComponentsManager, StackProjectManager}
import intellij.haskell.psi._
import intellij.haskell.util.{HaskellEditorUtil, StringUtil, TypeInfoUtil}

class ShowTypeAction extends AnAction {

  override def update(actionEvent: AnActionEvent) {
    HaskellEditorUtil.enableAction(onlyForProjectFile = true, actionEvent)
  }

  def actionPerformed(actionEvent: AnActionEvent) {
    ActionUtil.findActionContext(actionEvent).foreach(actionContext => {
      val editor = actionContext.editor
      val psiFile = actionContext.psiFile

      actionContext.selectionModel match {
        case Some(sm) => HaskellComponentsManager.findTypeInfoForSelection(psiFile, sm) match {
          case Some(ti) => HaskellEditorUtil.showHint(editor, StringUtil.escapeString(ti.typeSignature))
          case None => HaskellEditorUtil.showHint(editor, "Could not determine type for selection")
        }
        case _ => Option(psiFile.findElementAt(editor.getCaretModel.getOffset)).foreach { psiElement =>
          ShowTypeAction.showTypeHint(actionContext.project, editor, psiElement, psiFile)
        }
      }
    })
  }

}

object ShowTypeAction {
  def showTypeSelectionHint(editor: Editor, psiFile: PsiFile, selectionModel: SelectionModel): Unit = {
    HaskellComponentsManager.findTypeInfoForSelection(psiFile, selectionModel) match {
      case Some(ti) => HaskellEditorUtil.showHint(editor, StringUtil.escapeString(ti.typeSignature))
      case None => HaskellEditorUtil.showHint(editor, "Could not determine type for selection")
    }
  }

  def showTypeHint(project: Project, editor: Editor, psiElement: PsiElement, psiFile: PsiFile, sticky: Boolean = false): Unit = {
    if (!StackProjectManager.isBuilding(project)) {
      ApplicationManager.getApplication.executeOnPooledThread(new Runnable {
        override def run(): Unit = {
          TypeInfoUtil.preloadTypesAround(psiElement)
        }
      })
    }

    HaskellComponentsManager.findTypeInfoForElement(psiElement, forceGetInfo = true) match {
      case Some(ti) =>
        HaskellEditorUtil.showStatusBarInfoMessage(ti.typeSignature, project)
        HaskellEditorUtil.showHint(editor, StringUtil.escapeString(ti.typeSignature), sticky)
      case None if HaskellPsiUtil.findExpressionParent(psiElement).isDefined =>
        val moduleNames = HaskellPsiUtil.findImportDeclarations(psiFile).flatMap(_.getModuleName)
        val declaration = HaskellPsiUtil.findQualifiedNameParent(psiElement).flatMap(qualifiedNameElement => {
          val name = qualifiedNameElement.getName
          HaskellCompletionContributor.getAvailableImportedModuleIdentifiers(psiFile).find(mi => moduleNames.exists(_ == mi.moduleName) && mi.name == name).map(_.declaration).
            orElse(findModuleName(psiFile).flatMap(mn => HaskellComponentsManager.findLocalModuleIdentifiers(psiFile, mn).find(_.name == name).map(_.declaration))).
            orElse(HaskellPsiUtil.findHaskellDeclarationElements(psiFile).find(_.getIdentifierElements.exists(_.getName == name)).map(_.getText.replaceAll("""\s+""", " ")))
        })

        declaration match {
          case Some(d) =>
            HaskellEditorUtil.showStatusBarInfoMessage(d, project)
            HaskellEditorUtil.showHint(editor, StringUtil.escapeString(d), sticky)
          case None => showNoTypeInfoHint(editor, psiElement)
        }
      case None => showNoTypeInfoHint(editor, psiElement)
    }
  }

  private def findModuleName(psiFile: PsiFile): Option[String] = {
    HaskellPsiUtil.findModuleName(psiFile, runInRead = true)
  }

  private def showNoTypeInfoHint(editor: Editor, psiElement: PsiElement): Unit = {
    HaskellEditorUtil.showHint(editor, s"Could not determine type for ${StringUtil.escapeString(psiElement.getText)}")
  }
}

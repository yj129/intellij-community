/*
 * Copyright (c) 2005 JetBrains s.r.o. All Rights Reserved.
 */
package com.intellij.refactoring.rename.inplace;

import com.intellij.codeInsight.highlighting.HighlightManager;
import com.intellij.codeInsight.lookup.LookupItem;
import com.intellij.codeInsight.lookup.LookupItemUtil;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.codeInsight.template.*;
import com.intellij.codeInsight.template.impl.TemplateState;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.codeStyle.SuggestedNameInfo;
import com.intellij.psi.codeStyle.VariableKind;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import com.intellij.refactoring.util.NonCodeSearchDescriptionLocation;
import com.intellij.refactoring.util.TextOccurrencesUtil;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.containers.HashMap;
import com.intellij.util.containers.Stack;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * @author ven
 */
public class VariableInplaceRenamer {
  private static final Logger LOG = Logger.getInstance("#com.intellij.refactoring.rename.inplace.VariableInplaceRenamer");
  private final PsiVariable myElementToRename;
  @NonNls private static final String PRIMARY_VARIABLE_NAME = "PrimaryVariable";
  @NonNls private static final String OTHER_VARIABLE_NAME = "OtherVariable";
  private ArrayList<RangeHighlighter> myHighlighters;
  private final Editor myEditor;
  private final Project myProject;

  private final static Stack<VariableInplaceRenamer> ourRenamersStack = new Stack<VariableInplaceRenamer>();

  public VariableInplaceRenamer(PsiVariable elementToRename, Editor editor) {
    myElementToRename = elementToRename;
    myEditor = editor;
    myProject = myElementToRename.getProject();
  }

  public void performInplaceRename() {
    while (!ourRenamersStack.isEmpty()) {
      ourRenamersStack.peek().finish();
    }
    ourRenamersStack.push(this);

    final Collection<PsiReference> refs = ReferencesSearch.search(myElementToRename).findAll();
    final Map<TextRange, TextAttributes> rangesToHighlight = new HashMap<TextRange, TextAttributes>();
    //it is crucial to highlight AFTER the template is started, so we collect ranges first
    collectRangesToHighlight(rangesToHighlight, refs);

    final HighlightManager highlightManager = HighlightManager.getInstance(myProject);

    final PsiElement scope = myElementToRename instanceof PsiParameter
                             ? ((PsiParameter)myElementToRename).getDeclarationScope()
                             : PsiTreeUtil.getParentOfType(myElementToRename, PsiCodeBlock.class);
    final ResolveSnapshot snapshot = scope == null ? null : ResolveSnapshot.createSnapshot(scope);
    final TemplateBuilder builder = new TemplateBuilder(scope);

    final PsiIdentifier nameIdentifier = myElementToRename.getNameIdentifier();
    PsiElement selectedElement = getSelectedInEditorElement(nameIdentifier, refs, myEditor.getCaretModel().getOffset());
    if (!CommonRefactoringUtil.checkReadOnlyStatus(myProject, myElementToRename)) return;

    if (nameIdentifier != null) addVariable(nameIdentifier, selectedElement, builder);
    for (PsiReference ref : refs) {
      addVariable(ref.getElement(), selectedElement, builder);
    }
    CommandProcessor.getInstance().executeCommand(myProject, new Runnable() {
      public void run() {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          public void run() {
            int offset = myEditor.getCaretModel().getOffset();
            Template template = builder.buildInlineTemplate();
            template.setToShortenLongNames(false);
            assert scope != null;
            TextRange range = scope.getTextRange();
            assert range != null;
            myEditor.getCaretModel().moveToOffset(range.getStartOffset());
            myHighlighters = new ArrayList<RangeHighlighter>();
            TemplateManager.getInstance(myProject).startTemplate(myEditor, template, new TemplateEditingAdapter() {
              public void beforeTemplateFinished(final TemplateState templateState, Template template) {
                finish();

                if (snapshot != null) {
                  final String newName = templateState.getVariableValue(PRIMARY_VARIABLE_NAME).toString();
                  if (JavaPsiFacade.getInstance(myProject).getNameHelper().isIdentifier(newName)) {
                    ApplicationManager.getApplication().runWriteAction(new Runnable() {
                      public void run() {
                        snapshot.apply(newName);
                      }
                    });
                  }
                }
              }

              public void templateCancelled(Template template) {
                finish();
              }
            });

            //move to old offset
            final LookupImpl lookup = (LookupImpl)LookupManager.getActiveLookup(myEditor);
            if (lookup != null && lookup.getLookupStart() < offset) {
              lookup.setAdditionalPrefix(myEditor.getDocument().getCharsSequence().subSequence(lookup.getLookupStart(), offset).toString());
            }
            myEditor.getCaretModel().moveToOffset(offset);

            //add highlights
            addHighlights(rangesToHighlight, myEditor, myHighlighters, highlightManager);
          }

        });
      }

    }, RefactoringBundle.message("rename.title"), null);
  }

  private void finish() {
    ourRenamersStack.pop();

    if (myHighlighters != null) {
      final HighlightManager highlightManager = HighlightManager.getInstance(myProject);
      for (RangeHighlighter highlighter : myHighlighters) {
        highlightManager.removeSegmentHighlighter(myEditor, highlighter);
      }
    }
  }

  private void collectRangesToHighlight(Map<TextRange,TextAttributes> rangesToHighlight, Collection<PsiReference> refs) {
    EditorColorsManager colorsManager = EditorColorsManager.getInstance();
    PsiIdentifier nameId = myElementToRename.getNameIdentifier();
    LOG.assertTrue(nameId != null);
    rangesToHighlight.put(nameId.getTextRange(), colorsManager.getGlobalScheme().getAttributes(EditorColors.WRITE_SEARCH_RESULT_ATTRIBUTES));
    for (PsiReference ref : refs) {
      PsiElement element = ref.getElement();
      TextRange range = ref.getRangeInElement().shiftRight(element.getTextRange().getStartOffset());
      boolean isForWrite = element instanceof PsiReferenceExpression && PsiUtil.isAccessedForWriting((PsiExpression)element);
      TextAttributes attributes = colorsManager.getGlobalScheme().getAttributes(isForWrite ?
                                                                                EditorColors.WRITE_SEARCH_RESULT_ATTRIBUTES :
                                                                                EditorColors.SEARCH_RESULT_ATTRIBUTES);

      rangesToHighlight.put(range, attributes);
    }
  }

  private static void addHighlights(Map<TextRange,TextAttributes> ranges, Editor editor, ArrayList<RangeHighlighter> highlighters, HighlightManager highlightManager) {
    for (Map.Entry<TextRange, TextAttributes> entry : ranges.entrySet()) {
      TextRange range = entry.getKey();
      TextAttributes attributes = entry.getValue();
      highlightManager.addOccurrenceHighlight(editor, range.getStartOffset(), range.getEndOffset(), attributes, 0, highlighters, null);
    }

    for (RangeHighlighter highlighter : highlighters) {
      highlighter.setGreedyToLeft(true);
      highlighter.setGreedyToRight(true);
    }
  }

  private static PsiElement getSelectedInEditorElement(final PsiIdentifier nameIdentifier, final Collection<PsiReference> refs, final int offset) {
    if (nameIdentifier != null) {
      final TextRange range = nameIdentifier.getTextRange();
      if (contains(range, offset)) return nameIdentifier;
    }

    for (PsiReference ref : refs) {
      final PsiElement element = ref.getElement();
      final TextRange range = element.getTextRange();
      if (contains(range, offset)) return element;
    }

    LOG.assertTrue(false);
    return null;
  }

  private static boolean contains(final TextRange range, final int offset) {
    return range.getStartOffset() <= offset && offset <= range.getEndOffset();
  }

  private void addVariable(final PsiElement element, final PsiElement selectedElement, final TemplateBuilder builder) {
    if (element == selectedElement) {
      MyExpression expression = new MyExpression(myElementToRename.getName());
      builder.replaceElement(element, PRIMARY_VARIABLE_NAME, expression, true);
    }
    else {
      builder.replaceElement(element, OTHER_VARIABLE_NAME, PRIMARY_VARIABLE_NAME, false);
    }
  }

  public static boolean mayRenameInplace(PsiVariable elementToRename, final Editor editor, final PsiElement nameSuggestionContext) {
    if (!editor.getSettings().isVariableInplaceRenameEnabled()) return false;
    if (nameSuggestionContext != null && nameSuggestionContext.getContainingFile() != elementToRename.getContainingFile()) return false;
    if (!(elementToRename instanceof PsiLocalVariable) && !(elementToRename instanceof PsiParameter)) return false;
    SearchScope useScope = elementToRename.getUseScope();
    if (!(useScope instanceof LocalSearchScope)) return false;
    PsiElement[] scopeElements = ((LocalSearchScope) useScope).getScope();
    if (scopeElements.length > 1) return false; //assume there are no elements with use scopes with holes in'em
    PsiFile containingFile = elementToRename.getContainingFile();
    if (!PsiTreeUtil.isAncestor(containingFile, scopeElements[0], false)) return false;

    String stringToSearch = ElementDescriptionUtil.getElementDescription(elementToRename, true
                                                                                          ? NonCodeSearchDescriptionLocation.NON_JAVA
                                                                                          : NonCodeSearchDescriptionLocation.STRINGS_AND_COMMENTS);
    List<UsageInfo> usages = new ArrayList<UsageInfo>();
    if (stringToSearch != null) {
      TextOccurrencesUtil.addUsagesInStringsAndComments(elementToRename, stringToSearch, usages, new TextOccurrencesUtil.UsageInfoFactory() {
        public UsageInfo createUsageInfo(@NotNull PsiElement usage, int startOffset, int endOffset) {
          return new UsageInfo(usage); //will not need usage
        }
      }, true);
    }

    return usages.isEmpty();
  }

  private class MyExpression implements Expression {
    private final String myName;
    private final LookupItem[] myLookupItems;

    public MyExpression(String name) {
      myName = name;
      JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(myElementToRename.getProject());
      VariableKind variableKind = codeStyleManager.getVariableKind(myElementToRename);
      String propertyName = codeStyleManager.variableNameToPropertyName(myElementToRename.getName(), variableKind);
      SuggestedNameInfo nameInfo = codeStyleManager.suggestVariableName(variableKind, propertyName, null, myElementToRename.getType());
      List<String> names = new ArrayList<String>();
      for (String suggestedName : nameInfo.names) {
        if (!suggestedName.equals(myName)) names.add(suggestedName);
      }
      myLookupItems = new LookupItem[names.size()];
      for (int i = 0; i < myLookupItems.length; i++) {
        myLookupItems[i] = LookupItemUtil.objectToLookupItem(names.get(i));
      }
    }

    public LookupItem[] calculateLookupItems(ExpressionContext context) {
      return myLookupItems;
    }

    public Result calculateQuickResult(ExpressionContext context) {
      return new TextResult(myName);
    }

    public Result calculateResult(ExpressionContext context) {
      return new TextResult(myName);
    }
  }
}

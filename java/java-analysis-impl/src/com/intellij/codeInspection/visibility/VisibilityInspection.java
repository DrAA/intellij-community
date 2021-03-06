/*
 * Copyright 2000-2016 JetBrains s.r.o.
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

/*
 * Created by IntelliJ IDEA.
 * User: max
 * Date: Dec 21, 2001
 * Time: 8:46:41 PM
 * To change template for new class use
 * Code Style | Class Templates options (Tools | IDE Options).
 */
package com.intellij.codeInspection.visibility;

import com.intellij.ToolExtensionPoints;
import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInsight.daemon.GroupNames;
import com.intellij.codeInsight.daemon.impl.IdentifierUtil;
import com.intellij.codeInspection.*;
import com.intellij.codeInspection.ex.EntryPointsManager;
import com.intellij.codeInspection.reference.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.ui.components.panels.VerticalBox;
import com.intellij.usageView.UsageViewTypeLocation;
import com.intellij.util.VisibilityUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class VisibilityInspection extends GlobalJavaBatchInspectionTool {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.visibility.VisibilityInspection");
  public boolean SUGGEST_PACKAGE_LOCAL_FOR_MEMBERS = true;
  public boolean SUGGEST_PACKAGE_LOCAL_FOR_TOP_CLASSES = true;
  public boolean SUGGEST_PRIVATE_FOR_INNERS;
  private final Map<String, Boolean> myExtensions = new TreeMap<>();
  private static final String DISPLAY_NAME = InspectionsBundle.message("inspection.visibility.display.name");
  @NonNls public static final String SHORT_NAME = "WeakerAccess";
  private static final String CAN_BE_PRIVATE = InspectionsBundle.message("inspection.visibility.compose.suggestion", VisibilityUtil.toPresentableText(PsiModifier.PRIVATE));
  private static final String CAN_BE_PACKAGE_LOCAL = InspectionsBundle.message("inspection.visibility.compose.suggestion", VisibilityUtil.toPresentableText(PsiModifier.PACKAGE_LOCAL));
  private static final String CAN_BE_PROTECTED = InspectionsBundle.message("inspection.visibility.compose.suggestion", VisibilityUtil.toPresentableText(PsiModifier.PROTECTED));

  private class OptionsPanel extends JPanel {
    private final JCheckBox myPackageLocalForMembersCheckbox;
    private final JCheckBox myPrivateForInnersCheckbox;
    private final JCheckBox myPackageLocalForTopClassesCheckbox;

    private OptionsPanel() {
      super(new GridBagLayout());

      GridBagConstraints gc = new GridBagConstraints();
      gc.fill = GridBagConstraints.HORIZONTAL;
      gc.weightx = 1;
      gc.weighty = 0;
      gc.anchor = GridBagConstraints.NORTHWEST;

      myPackageLocalForMembersCheckbox = new JCheckBox(InspectionsBundle.message("inspection.visibility.option"));
      myPackageLocalForMembersCheckbox.setSelected(SUGGEST_PACKAGE_LOCAL_FOR_MEMBERS);
      myPackageLocalForMembersCheckbox.getModel().addItemListener(
        e -> SUGGEST_PACKAGE_LOCAL_FOR_MEMBERS = myPackageLocalForMembersCheckbox.isSelected());

      gc.gridy = 0;
      add(myPackageLocalForMembersCheckbox, gc);

      myPackageLocalForTopClassesCheckbox = new JCheckBox(InspectionsBundle.message("inspection.visibility.option1"));
      myPackageLocalForTopClassesCheckbox.setSelected(SUGGEST_PACKAGE_LOCAL_FOR_TOP_CLASSES);
      myPackageLocalForTopClassesCheckbox.getModel().addItemListener(
        e -> SUGGEST_PACKAGE_LOCAL_FOR_TOP_CLASSES = myPackageLocalForTopClassesCheckbox.isSelected());

      gc.gridy = 1;
      add(myPackageLocalForTopClassesCheckbox, gc);


      myPrivateForInnersCheckbox = new JCheckBox(InspectionsBundle.message("inspection.visibility.option2"));
      myPrivateForInnersCheckbox.setSelected(SUGGEST_PRIVATE_FOR_INNERS);
      myPrivateForInnersCheckbox.getModel().addItemListener(e -> SUGGEST_PRIVATE_FOR_INNERS = myPrivateForInnersCheckbox.isSelected());

      gc.gridy = 2;
      add(myPrivateForInnersCheckbox, gc);

      final ExtensionPoint<EntryPoint> point = Extensions.getRootArea().getExtensionPoint(ToolExtensionPoints.DEAD_CODE_TOOL);
      for (EntryPoint entryPoint : point.getExtensions()) {
        if (entryPoint instanceof EntryPointWithVisibilityLevel) {
          gc.gridy++;
          final JCheckBox checkBox = new JCheckBox(((EntryPointWithVisibilityLevel)entryPoint).getTitle());
          checkBox.setSelected(myExtensions.getOrDefault(((EntryPointWithVisibilityLevel)entryPoint).getId(), true));
          checkBox.addActionListener(e -> myExtensions.put(((EntryPointWithVisibilityLevel)entryPoint).getId(), checkBox.isSelected()));
          add(checkBox, gc);
        }
      }
      
      gc.gridy++;
      gc.weighty = 1;
      add(new VerticalBox(), gc);
    }
  }

  @Override
  public JComponent createOptionsPanel() {
    return new OptionsPanel();
  }

  @Nullable
  @Override
  public LocalInspectionTool getSharedLocalInspectionTool() {
    return new AccessCanBeTightenedInspection(this);
  }

  @Override
  @NotNull
  public String getDisplayName() {
    return DISPLAY_NAME;
  }

  @Override
  @NotNull
  public String getGroupDisplayName() {
    return GroupNames.DECLARATION_REDUNDANCY;
  }

  @Override
  @NotNull
  public String getShortName() {
    return SHORT_NAME;
  }

  @Override
  @Nullable
  public CommonProblemDescriptor[] checkElement(@NotNull final RefEntity refEntity,
                                                @NotNull final AnalysisScope scope,
                                                @NotNull final InspectionManager manager,
                                                @NotNull final GlobalInspectionContext globalContext,
                                                @NotNull final ProblemDescriptionsProcessor processor) {
    if (!(refEntity instanceof RefJavaElement)) {
      return null;
    }
    final RefJavaElement refElement = (RefJavaElement)refEntity;

    if (refElement instanceof RefParameter) return null;
    if (refElement.isSyntheticJSP()) return null;

    int minLevel = -1;
    //ignore entry points.
    if (refElement.isEntry()) {
      minLevel = getMinVisibilityLevel(refElement);
      if (minLevel <= 0) return null;
    }

    //ignore implicit constructors. User should not be able to see them.
    if (refElement instanceof RefImplicitConstructor) return null;

    if (refElement instanceof RefField) {
      final Boolean isEnumConstant = refElement.getUserData(RefField.ENUM_CONSTANT);
      if (isEnumConstant != null && isEnumConstant.booleanValue()) return null;
    }

    //ignore library override methods.
    if (refElement instanceof RefMethod) {
      RefMethod refMethod = (RefMethod) refElement;
      if (refMethod.isExternalOverride()) return null;
    }

    //ignore anonymous classes. They do not have access modifiers.
    if (refElement instanceof RefClass) {
      RefClass refClass = (RefClass) refElement;
      if (refClass.isAnonymous() || refClass.isServlet() || refClass.isApplet() || refClass.isLocalClass()) return null;
    }


    //ignore unreferenced code. They could be a potential entry points.
    if (refElement.getInReferences().isEmpty()) {
      if (minLevel <= 0) {
        minLevel = getMinVisibilityLevel(refElement);
        if (minLevel <= 0) return null;
      }
      String weakestAccess = PsiUtil.getAccessModifier(minLevel);
      if (weakestAccess != refElement.getAccessModifier()) {
        return createDescriptions(refElement, weakestAccess, manager, globalContext);
      }
    }

    if (refElement instanceof RefClass) {
      if (isTopLevelClass(refElement) && minLevel <= 0 && !SUGGEST_PACKAGE_LOCAL_FOR_TOP_CLASSES) return null;
    }

    //ignore interface members. They always have public access modifier.
    if (refElement.getOwner() instanceof RefClass) {
      RefClass refClass = (RefClass) refElement.getOwner();
      if (refClass.isInterface()) return null;
    }
    String access = getPossibleAccess(refElement, minLevel <= 0 ? PsiUtil.ACCESS_LEVEL_PRIVATE : minLevel);
    if (access != refElement.getAccessModifier() && access != null) {
      return createDescriptions(refElement, access, manager, globalContext);
    }
    return null;
  }

  @NotNull
  private static CommonProblemDescriptor[] createDescriptions(RefElement refElement, String access,
                                                              @NotNull InspectionManager manager,
                                                              @NotNull GlobalInspectionContext globalContext) {
    final PsiElement element = refElement.getElement();
    final PsiElement nameIdentifier = element != null ? IdentifierUtil.getNameIdentifier(element) : null;
    if (nameIdentifier != null) {
      final String message;
      String quickFixName = "Make " + ElementDescriptionUtil.getElementDescription(element, UsageViewTypeLocation.INSTANCE) + " ";
      if (access.equals(PsiModifier.PRIVATE)) {
        message = CAN_BE_PRIVATE;
        quickFixName += VisibilityUtil.toPresentableText(PsiModifier.PRIVATE);
      }
      else {
        if (access.equals(PsiModifier.PACKAGE_LOCAL)) {
          message = CAN_BE_PACKAGE_LOCAL;
          quickFixName += VisibilityUtil.toPresentableText(PsiModifier.PACKAGE_LOCAL);
        }
        else {
          message = CAN_BE_PROTECTED;
          quickFixName += VisibilityUtil.toPresentableText(PsiModifier.PROTECTED);
        }
      }
      return new ProblemDescriptor[]{manager.createProblemDescriptor(nameIdentifier,
                                                                     message,
                                                                     new AcceptSuggestedAccess(globalContext.getRefManager(), access, quickFixName),
                                                                     ProblemHighlightType.GENERIC_ERROR_OR_WARNING, false)};
    }
    return CommonProblemDescriptor.EMPTY_ARRAY;
  }

  int getMinVisibilityLevel(PsiMember member) {
    return Arrays.stream(ExtensionPointName.<EntryPoint>create(ToolExtensionPoints.DEAD_CODE_TOOL).getExtensions())
      .filter(point -> point instanceof EntryPointWithVisibilityLevel && 
                       myExtensions.getOrDefault(((EntryPointWithVisibilityLevel)point).getId(), true))
      .mapToInt(point -> ((EntryPointWithVisibilityLevel)point).getMinVisibilityLevel(member))
      .max().orElse(-1);
  }

  private int getMinVisibilityLevel(RefJavaElement refElement) {
    PsiElement element = refElement.getElement();
    if (element instanceof PsiMember) {
      return getMinVisibilityLevel((PsiMember)element);
    }
    return -1;
  }

  @Nullable
  @PsiModifier.ModifierConstant
  private String getPossibleAccess(@NotNull RefJavaElement refElement, int minLevel) {
    String curAccess = refElement.getAccessModifier();
    String weakestAccess = PsiUtil.getAccessModifier(minLevel);

    if (isTopLevelClass(refElement) || isCalledOnSubClasses(refElement)) {
      weakestAccess = PsiModifier.PACKAGE_LOCAL;
    }

    if (isAbstractMethod(refElement)) {
      weakestAccess = PsiModifier.PROTECTED;
    }

    if (curAccess == weakestAccess) return curAccess;

    while (true) {
      String weakerAccess = getWeakerAccess(curAccess, refElement);
      if (weakerAccess == null || RefJavaUtil.getInstance().compareAccess(weakerAccess, weakestAccess) < 0) break;
      if (isAccessible(refElement, weakerAccess)) {
        curAccess = weakerAccess;
      }
      else {
        break;
      }
    }

    return curAccess;
  }

  private static boolean isCalledOnSubClasses(RefElement refElement) {
    return refElement instanceof RefMethod && ((RefMethod)refElement).isCalledOnSubClass();
  }

  private static boolean isAbstractMethod(RefElement refElement) {
    return refElement instanceof RefMethod && ((RefMethod) refElement).isAbstract();
  }

  private static boolean isTopLevelClass(RefElement refElement) {
    return refElement instanceof RefClass && RefJavaUtil.getInstance().getTopLevelClass(refElement) == refElement;
  }

  @Nullable
  @PsiModifier.ModifierConstant
  private String getWeakerAccess(@PsiModifier.ModifierConstant String curAccess, RefElement refElement) {
    if (curAccess == PsiModifier.PUBLIC) {
      return isTopLevelClass(refElement) ? PsiModifier.PACKAGE_LOCAL : PsiModifier.PROTECTED;
    }
    if (curAccess == PsiModifier.PROTECTED) {
      return SUGGEST_PACKAGE_LOCAL_FOR_MEMBERS ? PsiModifier.PACKAGE_LOCAL : PsiModifier.PRIVATE;
    }
    if (curAccess == PsiModifier.PACKAGE_LOCAL) {
      return PsiModifier.PRIVATE;
    }

    return null;
  }

  private boolean isAccessible(RefJavaElement to, @PsiModifier.ModifierConstant String accessModifier) {

    for (RefElement refElement : to.getInReferences()) {
      if (!isAccessibleFrom(refElement, to, accessModifier)) return false;
    }

    if (to instanceof RefMethod) {
      RefMethod refMethod = (RefMethod) to;

      if (refMethod.isAbstract() && (refMethod.getDerivedMethods().isEmpty() || refMethod.getAccessModifier() == PsiModifier.PRIVATE)) return false;

      for (RefMethod refOverride : refMethod.getDerivedMethods()) {
        if (!isAccessibleFrom(refOverride, to, accessModifier)) return false;
      }

      for (RefMethod refSuper : refMethod.getSuperMethods()) {
        if (RefJavaUtil.getInstance().compareAccess(refSuper.getAccessModifier(), accessModifier) > 0) return false;
      }
    }

    if (to instanceof RefClass) {
      RefClass refClass = (RefClass) to;
      for (RefClass subClass : refClass.getSubClasses()) {
        if (!isAccessibleFrom(subClass, to, accessModifier)) return false;
      }

      List children = refClass.getChildren();
      for (Object refElement : children) {
        if (!isAccessible((RefJavaElement)refElement, accessModifier)) return false;
      }

      for (final RefElement refElement : refClass.getInTypeReferences()) {
        if (!isAccessibleFrom(refElement, refClass, accessModifier)) return false;
      }

      List<RefJavaElement> classExporters = ((RefClassImpl)refClass).getClassExporters();
      if (classExporters != null) {
        for (RefJavaElement refExporter : classExporters) {
          if (getAccessLevel(accessModifier) < getAccessLevel(refExporter.getAccessModifier())) return false;
        }
      }
    }

    return true;
  }

  private static int getAccessLevel(@PsiModifier.ModifierConstant String access) {
    if (access == PsiModifier.PRIVATE) return 1;
    if (access == PsiModifier.PACKAGE_LOCAL) return 2;
    if (access == PsiModifier.PROTECTED) return 3;
    return 4;
  }

  private boolean isAccessibleFrom(RefElement from, RefJavaElement to, String accessModifier) {
    if (accessModifier == PsiModifier.PUBLIC) return true;

    final RefJavaUtil refUtil = RefJavaUtil.getInstance();
    if (accessModifier == PsiModifier.PACKAGE_LOCAL) {
      return RefJavaUtil.getPackage(from) == RefJavaUtil.getPackage(to);
    }

    RefClass fromTopLevel = refUtil.getTopLevelClass(from);
    RefClass toTopLevel = refUtil.getTopLevelClass(to);
    RefClass fromOwner = refUtil.getOwnerClass(from);
    RefClass toOwner = refUtil.getOwnerClass(to);

    if (accessModifier == PsiModifier.PROTECTED) {
      if (to instanceof RefJavaElementImpl && ((RefJavaElementImpl)to).isUsedQualifiedOutsidePackage()) {
        return false;
      }
      if (SUGGEST_PRIVATE_FOR_INNERS) {
        return fromTopLevel != null && refUtil.isInheritor(fromTopLevel, toOwner)
               || fromOwner != null && refUtil.isInheritor(fromOwner, toTopLevel)
               || toOwner != null && refUtil.getOwnerClass(toOwner) == from;
      }

      return fromTopLevel != null && refUtil.isInheritor(fromTopLevel, toOwner);
    }

    if (accessModifier == PsiModifier.PRIVATE) {
      if (SUGGEST_PRIVATE_FOR_INNERS) {
        final PsiClass fromTopLevelElement = fromTopLevel != null ? fromTopLevel.getElement() : null;
        if (fromTopLevelElement != null && isInExtendsList(to, fromTopLevelElement.getExtendsList())) return false;
        if (fromTopLevelElement != null && isInExtendsList(to, fromTopLevelElement.getImplementsList())) return false;
        if (fromTopLevelElement != null && isInAnnotations(to, fromTopLevelElement)) return false;
        return fromTopLevel == toOwner || fromOwner == toTopLevel || toOwner != null && (
          refUtil.getOwnerClass(toOwner) == from || from instanceof RefMethod && toOwner == ((RefMethod)from).getOwnerClass() ||
          from instanceof RefField && toOwner == ((RefField)from).getOwnerClass());
      }

      if (fromOwner != null && fromOwner.isStatic() && !to.isStatic() && refUtil.isInheritor(fromOwner, toOwner)) return false;

      if (fromTopLevel == toOwner) {
        if (from instanceof RefClass && to instanceof RefClass) {
          final PsiClass fromClass = ((RefClass)from).getElement();
          LOG.assertTrue(fromClass != null);
          if (isInExtendsList(to, fromClass.getExtendsList())) return false;
          if (isInExtendsList(to, fromClass.getImplementsList())) return false;
        }

        return true;
      }
    }

    return false;
  }

  private static boolean isInAnnotations(final RefJavaElement to, @NotNull final PsiClass fromTopLevelElement) {
    final PsiModifierList modifierList = fromTopLevelElement.getModifierList();
    if (modifierList == null) return false;
    final PsiElement toElement = to.getElement();

    final boolean [] resolved = {false};
    modifierList.accept(new JavaRecursiveElementWalkingVisitor() {
      @Override
      public void visitReferenceExpression(PsiReferenceExpression expression) {
        if (resolved[0]) return;
        super.visitReferenceExpression(expression);
        if (expression.resolve() == toElement) {
          resolved[0] = true;
        }
      }
    });
    return resolved[0];
  }

  private static boolean isInExtendsList(final RefJavaElement to, final PsiReferenceList extendsList) {
    if (extendsList != null) {
      final PsiJavaCodeReferenceElement[] referenceElements = extendsList.getReferenceElements();
      for (PsiJavaCodeReferenceElement referenceElement : referenceElements) {
        final PsiReferenceParameterList parameterList = referenceElement.getParameterList();
        if (parameterList != null) {
          for (PsiType type : parameterList.getTypeArguments()) {
            if (extendsList.getManager().areElementsEquivalent(PsiUtil.resolveClassInType(type), to.getElement())) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }


  @Override
  protected boolean queryExternalUsagesRequests(@NotNull final RefManager manager,
                                                @NotNull final GlobalJavaInspectionContext globalContext,
                                                @NotNull final ProblemDescriptionsProcessor processor) {
    final EntryPointsManager entryPointsManager = globalContext.getEntryPointsManager(manager);
    for (RefElement entryPoint : entryPointsManager.getEntryPoints()) {
      //don't ignore entry points with explicit visibility requirements
      if (entryPoint instanceof RefJavaElement && getMinVisibilityLevel((RefJavaElement)entryPoint) > 0) {
        continue;
      }
      ignoreElement(processor, entryPoint);
    }

    ExtensionPoint<VisibilityExtension> point = Extensions.getRootArea().getExtensionPoint(ToolExtensionPoints.VISIBLITY_TOOL);
    for (VisibilityExtension addin : point.getExtensions()) {
      addin.fillIgnoreList(manager, processor);
    }
    manager.iterate(new RefJavaVisitor() {
      @Override public void visitElement(@NotNull final RefEntity refEntity) {
        if (!(refEntity instanceof RefElement)) return;
        if (processor.getDescriptions(refEntity) == null) return;
        refEntity.accept(new RefJavaVisitor() {
          @Override public void visitField(@NotNull final RefField refField) {
            if (refField.getAccessModifier() != PsiModifier.PRIVATE) {
              globalContext.enqueueFieldUsagesProcessor(refField, psiReference -> {
                ignoreElement(processor, refField);
                return false;
              });
            }
          }

          @Override public void visitMethod(@NotNull final RefMethod refMethod) {
            if (!refMethod.isExternalOverride() && refMethod.getAccessModifier() != PsiModifier.PRIVATE &&
                !(refMethod instanceof RefImplicitConstructor)) {
              globalContext.enqueueDerivedMethodsProcessor(refMethod, derivedMethod -> {
                ignoreElement(processor, refMethod);
                return false;
              });

              globalContext.enqueueMethodUsagesProcessor(refMethod, psiReference -> {
                ignoreElement(processor, refMethod);
                return false;
              });
            }
          }

          @Override public void visitClass(@NotNull final RefClass refClass) {
            if (!refClass.isAnonymous()) {
              globalContext.enqueueDerivedClassesProcessor(refClass, inheritor -> {
                ignoreElement(processor, refClass);
                return false;
              });

              globalContext.enqueueClassUsagesProcessor(refClass, psiReference -> {
                ignoreElement(processor, refClass);
                return false;
              });

              final RefMethod defaultConstructor = refClass.getDefaultConstructor();
              if (entryPointsManager.isAddNonJavaEntries() && defaultConstructor != null) {
                final PsiClass psiClass = refClass.getElement();
                String qualifiedName = psiClass != null ? psiClass.getQualifiedName() : null;
                if (qualifiedName != null) {
                  final Project project = manager.getProject();
                  PsiSearchHelper.SERVICE.getInstance(project)
                    .processUsagesInNonJavaFiles(qualifiedName, (file, startOffset, endOffset) -> {
                      entryPointsManager.addEntryPoint(defaultConstructor, false);
                      ignoreElement(processor, defaultConstructor);
                      return false;
                    }, GlobalSearchScope.projectScope(project));
                }
              }
            }
          }
        });

      }
    });
    return false;
  }

  private static void ignoreElement(@NotNull ProblemDescriptionsProcessor processor, @NotNull RefEntity refElement){
    processor.ignoreElement(refElement);

    if (refElement instanceof RefClass) {
      RefClass refClass = (RefClass) refElement;
      RefMethod defaultConstructor = refClass.getDefaultConstructor();
      if (defaultConstructor != null) {
        processor.ignoreElement(defaultConstructor);
        return;
      }
    }

    RefEntity owner = refElement.getOwner();
    if (owner instanceof RefElement) {
      processor.ignoreElement(owner);
    }
  }

  @Override
  public void compose(@NotNull final StringBuffer buf, @NotNull final RefEntity refEntity, @NotNull final HTMLComposer composer) {
    composer.appendElementInReferences(buf, (RefElement)refEntity);
  }

  @Override
  @Nullable
  public QuickFix getQuickFix(final String hint) {
    return new AcceptSuggestedAccess(null, hint, null);
  }

  @Override
  @Nullable
  public String getHint(@NotNull final QuickFix fix) {
    return ((AcceptSuggestedAccess)fix).myHint;
  }

  @Override
  public void writeSettings(@NotNull Element node) {
    super.writeSettings(node);
    for (Map.Entry<String, Boolean> entry : myExtensions.entrySet()) {
      if (!entry.getValue()) {
        node.addContent(new Element("disabledExtension").setAttribute("id", entry.getKey()));
      }
    }
  }

  @Override
  public void readSettings(@NotNull Element node) {
    super.readSettings(node);
    for (Element extension : node.getChildren("disabledExtension")) {
      final String id = extension.getAttributeValue("id");
      if (id != null) {
        myExtensions.put(id, false);
      }
    }
  }

  private static class AcceptSuggestedAccess implements LocalQuickFix{
    private final RefManager myManager;
    @PsiModifier.ModifierConstant private final String myHint;
    private final String myName;

    private AcceptSuggestedAccess(final RefManager manager, @PsiModifier.ModifierConstant String hint, String name) {
      myManager = manager;
      myHint = hint;
      myName = name;
    }

    @Override
    @NotNull
    public String getName() {
      return myName != null ? myName : getFamilyName();
    }

    @Override
    @NotNull
    public String getFamilyName() {
      return InspectionsBundle.message("inspection.visibility.accept.quickfix");
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      final PsiModifierListOwner element = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiModifierListOwner.class);
      if (element != null) {
        RefElement refElement = null;
        if (myManager != null) {
          refElement = myManager.getReference(element);
        }
        if (element instanceof PsiVariable) {
          ((PsiVariable)element).normalizeDeclaration();
        }

        PsiModifierList list = element.getModifierList();

        LOG.assertTrue(list != null);

        if (element instanceof PsiMethod) {
          PsiMethod psiMethod = (PsiMethod)element;
          PsiClass containingClass = psiMethod.getContainingClass();
          if (containingClass != null && containingClass.getParent() instanceof PsiFile &&
              myHint == PsiModifier.PRIVATE &&
              list.hasModifierProperty(PsiModifier.FINAL)) {
            list.setModifierProperty(PsiModifier.FINAL, false);
          }
        }

        list.setModifierProperty(myHint, true);
        if (refElement instanceof RefJavaElement) {
          RefJavaUtil.getInstance().setAccessModifier((RefJavaElement)refElement, myHint);
        }
      }
    }
  }
}

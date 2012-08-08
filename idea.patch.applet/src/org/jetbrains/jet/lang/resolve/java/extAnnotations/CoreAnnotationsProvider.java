/*
 * Copyright 2010-2012 JetBrains s.r.o.
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

package org.jetbrains.jet.lang.resolve.java.extAnnotations;

import org.jetbrains.jet.internal.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.jet.internal.com.intellij.openapi.util.JDOMUtil;
import org.jetbrains.jet.internal.com.intellij.openapi.util.io.StreamUtil;
import org.jetbrains.jet.internal.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.jet.internal.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.jet.internal.com.intellij.psi.*;
import org.jetbrains.jet.internal.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.jet.internal.com.intellij.util.IncorrectOperationException;
import org.jetbrains.jet.internal.com.intellij.util.StringBuilderSpinAllocator;
import org.jetbrains.jet.internal.com.intellij.util.containers.ConcurrentWeakHashMap;
import org.jetbrains.jet.internal.com.intellij.util.containers.ConcurrentWeakValueHashMap;
import org.jetbrains.jet.internal.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.jet.internal.com.intellij.util.containers.MultiMap;
import org.jetbrains.jet.internal.org.jdom.Document;
import org.jetbrains.jet.internal.org.jdom.Element;
import org.jetbrains.jet.internal.org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

/**
 * This class is copied from IDEA.
 * This class should be eliminated when Kotlin will depend on IDEA 12.x (KT-2326)
 * @author Evgeny Gerashchenko
 * @since 6/26/12
 */
@Deprecated
public class CoreAnnotationsProvider extends ExternalAnnotationsProvider {
    static {
        // This is an ugly workaround for JDOM 1.1 used from application started from Ant 1.8 without forking
//        System.setProperty("javax.xml.parsers.SAXParserFactory", "com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl");
    }

    private static final Logger LOG = Logger.getInstance("#" + CoreAnnotationsProvider.class.getName());
    @NotNull private static final List<PsiFile> NULL = new ArrayList<PsiFile>();
    @NotNull private final ConcurrentMap<String, List<PsiFile>>
            myExternalAnnotations = new ConcurrentWeakValueHashMap<String, List<PsiFile>>();

    private List<VirtualFile> externalAnnotationsRoots = new ArrayList<VirtualFile>();

    public CoreAnnotationsProvider() {
    }

    @Nullable
    private static String getExternalName(PsiModifierListOwner listOwner, boolean showParamName) {
        return PsiFormatUtil.getExternalName(listOwner, showParamName, Integer.MAX_VALUE);
    }

    @Nullable
    private static String getFQN(String packageName, @Nullable VirtualFile virtualFile) {
        if (virtualFile == null) return null;
        return StringUtil.getQualifiedName(packageName, virtualFile.getNameWithoutExtension());
    }

    @Nullable
    protected static String getNormalizedExternalName(@NotNull PsiModifierListOwner owner) {
        String externalName = getExternalName(owner, true);
        if (externalName != null) {
            if (owner instanceof PsiParameter && owner.getParent() instanceof PsiParameterList) {
                final PsiMethod method = PsiTreeUtil.getParentOfType(owner, PsiMethod.class);
                if (method != null) {
                    externalName =
                            externalName.substring(0, externalName.lastIndexOf(' ') + 1) + method.getParameterList().getParameterIndex((PsiParameter)owner);
                }
            }
            final int idx = externalName.indexOf('(');
            if (idx == -1) return externalName;
            final StringBuilder buf = StringBuilderSpinAllocator.alloc();
            try {
                final int rightIdx = externalName.indexOf(')');
                final String[] params = externalName.substring(idx + 1, rightIdx).split(",");
                buf.append(externalName.substring(0, idx + 1));
                for (String param : params) {
                    param = param.trim();
                    final int spaceIdx = param.indexOf(' ');
                    buf.append(spaceIdx > -1 ? param.substring(0, spaceIdx) : param).append(", ");
                }
                return StringUtil.trimEnd(buf.toString(), ", ") + externalName.substring(rightIdx);
            }
            finally {
                StringBuilderSpinAllocator.dispose(buf);
            }
        }
        return externalName;
    }

    @Override
    @Nullable
    public PsiAnnotation findExternalAnnotation(@NotNull final PsiModifierListOwner listOwner, @NotNull final String annotationFQN) {
        return collectExternalAnnotations(listOwner).get(annotationFQN);
    }

    @Override
    @Nullable
    public PsiAnnotation[] findExternalAnnotations(@NotNull final PsiModifierListOwner listOwner) {
        final Map<String, PsiAnnotation> result = collectExternalAnnotations(listOwner);
        return result.isEmpty() ? null : result.values().toArray(new PsiAnnotation[result.size()]);
    }

    private final Map<PsiModifierListOwner, Map<String, PsiAnnotation>> cache = new ConcurrentWeakHashMap<PsiModifierListOwner, Map<String, PsiAnnotation>>();
    @NotNull
    private Map<String, PsiAnnotation> collectExternalAnnotations(@NotNull final PsiModifierListOwner listOwner) {
        Map<String, PsiAnnotation> map = cache.get(listOwner);
        if (map == null) {
            map = doCollect(listOwner);
            cache.put(listOwner, map);
        }
        return map;
    }

    public void addExternalAnnotationsRoot(VirtualFile externalAnnotationsRoot) {
        externalAnnotationsRoots.add(externalAnnotationsRoot);
    }

    private Map<PsiFile, MultiMap<String, AnnotationData>> annotationsFileToData = new HashMap<PsiFile, MultiMap<String, AnnotationData>>();
    private Map<PsiFile, Long> annotationsFileToModificationStamp = new HashMap<PsiFile, Long>();
    @NotNull
    private MultiMap<String, AnnotationData> getDataFromFile(@NotNull PsiFile file) {
        if (annotationsFileToData.containsKey(file) && file.getModificationStamp() == annotationsFileToModificationStamp.get(file)) {
            return annotationsFileToData.get(file);
        }
        else {
            MultiMap<String, AnnotationData> data = new MultiMap<String, AnnotationData>();
            annotationsFileToData.put(file, data);
            annotationsFileToModificationStamp.put(file, file.getModificationStamp());

            Document document;
            try {
                //noinspection ConstantConditions
                document = JDOMUtil.loadDocument(escapeAttributes(StreamUtil.readText(file.getVirtualFile().getInputStream())));
            }
            catch (IOException e) {
                LOG.error(e);
                return data;
            }
            catch (JDOMException e) {
                LOG.error(e);
                return data;
            }
            Element rootElement = document.getRootElement();
            if (rootElement == null) return data;

            //noinspection unchecked
            for (Element element : (List<Element>) rootElement.getChildren()) {
                String ownerName = element.getAttributeValue("name");
                if (ownerName == null) continue;
                //noinspection unchecked
                for (Element annotationElement : (List<Element>) element.getChildren()) {
                    String annotationFQN = annotationElement.getAttributeValue("name");
                    StringBuilder buf = new StringBuilder();
                    //noinspection unchecked
                    for (Element annotationParameter : (List<Element>) annotationElement.getChildren()) {
                        buf.append(",");
                        String nameValue = annotationParameter.getAttributeValue("name");
                        if (nameValue != null) {
                            buf.append(nameValue).append("=");
                        }
                        buf.append(annotationParameter.getAttributeValue("val"));
                    }
                    String annotationText = "@" + annotationFQN + (buf.length() > 0 ? "(" + StringUtil.trimStart(buf.toString(), ",") + ")" : "");
                    data.putValue(ownerName, new AnnotationData(annotationFQN, annotationText));
                }
            }

            return data;
        }
    }

    private Map<String, PsiAnnotation> doCollect(@NotNull PsiModifierListOwner listOwner) {
        final List<PsiFile> files = findExternalAnnotationsFiles(listOwner);
        if (files == null) {
            return Collections.emptyMap();
        }
        Map<String, PsiAnnotation> result = new HashMap<String, PsiAnnotation>();
        String externalName = getExternalName(listOwner, false);
        String oldExternalName = getNormalizedExternalName(listOwner);

        for (PsiFile file : files) {
            if (!file.isValid()) continue;
            MultiMap<String, AnnotationData> fileData = getDataFromFile(file);
            for (AnnotationData annotationData : ContainerUtil.concat(fileData.get(externalName), fileData.get(oldExternalName))) {
                try {
                    result.put(annotationData.annotationClassFqName,
                            JavaPsiFacade.getInstance(listOwner.getProject()).getElementFactory().createAnnotationFromText(
                                    annotationData.annotationText, null));
                }
                catch (IncorrectOperationException e) {
                    LOG.error(e);
                }
            }
        }
        return result;
    }

    @Nullable
    private List<PsiFile> findExternalAnnotationsFiles(@NotNull PsiModifierListOwner listOwner) {
        final PsiFile containingFile = listOwner.getContainingFile();
        if (!(containingFile instanceof PsiJavaFile)) {
            return null;
        }
        final PsiJavaFile javaFile = (PsiJavaFile)containingFile;
        final String packageName = javaFile.getPackageName();
        final VirtualFile virtualFile = containingFile.getVirtualFile();
        String fqn = getFQN(packageName, virtualFile);
        if (fqn == null) return null;
        final List<PsiFile> files = myExternalAnnotations.get(fqn);
        if (files == NULL) return null;
        if (files != null) {
            for (Iterator<PsiFile> it = files.iterator(); it.hasNext();) {
                if (!it.next().isValid()) it.remove();
            }
            return files;
        }

        if (virtualFile == null) {
            return null;
        }

        List<PsiFile> possibleAnnotationsXmls = new ArrayList<PsiFile>();
        for (VirtualFile root : externalAnnotationsRoots) {
            final VirtualFile ext = root.findFileByRelativePath(packageName.replace(".", "/") + "/" + "annotations.xml");
            if (ext == null) continue;
            final PsiFile psiFile = listOwner.getManager().findFile(ext);
            possibleAnnotationsXmls.add(psiFile);
        }
        if (!possibleAnnotationsXmls.isEmpty()) {
            myExternalAnnotations.put(fqn, possibleAnnotationsXmls);
            return possibleAnnotationsXmls;
        }
        myExternalAnnotations.put(fqn, NULL);
        return null;
    }


    // This method is used for legacy reasons.
    // Old external annotations sometimes are bad XML: they have "<" and ">" characters in attributes values. To prevent SAX parser from
    // failing, we escape attributes values.
    @NotNull
    private static String escapeAttributes(@NotNull String invalidXml) {
        // We assume that XML has single- and double-quote characters only for attribute values, therefore we don't any complex parsing,
        // just split by ['"] regexp instead

        // For PERFORMANCE REASONS this is written by hand instead of using regular expressions
        // This implementation turned out to be too slow:
        //    String[] split = invalidXml.split("[\"\']");
        //    assert split.length % 2 == 1;
        //    for (int i = 1; i < split.length; i += 2) {
        //        split[i] = split[i].replace("<", "&lt;").replace(">", "&gt;");
        //    }
        //    return StringUtil.join(split, "\"");

        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < invalidXml.length(); i++) {
            char c = invalidXml.charAt(i);
            switch (c) {
                case '\'':
                case '\"':
                    inQuotes = !inQuotes;
                    sb.append('\"');
                    break;
                case '<':
                    if (inQuotes) {
                        sb.append("&lt;");
                    }
                    else {
                        sb.append(c);
                    }
                    break;
                case '>':
                    if (inQuotes) {
                        sb.append("&gt;");
                    }
                    else {
                        sb.append(c);
                    }
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    private static class AnnotationData {
        public String annotationClassFqName;
        public String annotationText;

        private AnnotationData(String annotationClassFqName, String annotationText) {
            this.annotationClassFqName = annotationClassFqName;
            this.annotationText = annotationText;
        }
    }
}
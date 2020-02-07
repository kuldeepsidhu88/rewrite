/*
 * Copyright 2020 the original authors.
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
 */
package com.netflix.rewrite.tree.visitor.refactor.op;

import com.netflix.rewrite.internal.lang.Nullable;
import com.netflix.rewrite.tree.*;
import com.netflix.rewrite.tree.visitor.refactor.AstTransform;
import com.netflix.rewrite.tree.visitor.refactor.RefactorVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static com.netflix.rewrite.tree.Tr.randomId;
import static java.util.Collections.emptyList;

/**
 * NOTE: Does not currently transform all possible type references, and accomplishing this would be non-trivial.
 * For example, a method invocation select might refer to field `A a` whose type has now changed to `A2`, and so the type
 * on the select should change as well. But how do we identify the set of all method selects which refer to `a`? Suppose
 * it were prefixed like `this.a`, or `MyClass.this.a`, or indirectly via a separate method call like `getA()` where `getA()`
 * is defined on the super class.
 */
public class ChangeType extends RefactorVisitor<Tree> {
    String from;
    Type.Class toClassType;

    public ChangeType(String from, String to) {
        this.from = from;
        this.toClassType = Type.Class.build(to);
    }

    // NOTE: a type change is possible anywhere a Tr.FieldAccess or Tr.Ident is possible, but not every FieldAccess or Ident
    // represents a type (could represent a variable name, etc.)

    @Override
    protected String getRuleName() {
        return "change-type";
    }

    @Override
    public List<AstTransform<Tree>> visitAnnotation(Tr.Annotation annotation) {
        List<AstTransform<Tree>> changes = super.visitAnnotation(annotation);
        changes.addAll(transformName(annotation, annotation.getAnnotationType(), Tr.Annotation::withAnnotationType));
        return changes;
    }

    @Override
    public List<AstTransform<Tree>> visitArrayType(Tr.ArrayType arrayType) {
        List<AstTransform<Tree>> changes = super.visitArrayType(arrayType);
        changes.addAll(transformName(arrayType, arrayType.getElementType(), Tr.ArrayType::withElementType));
        return changes;
    }

    @Override
    public List<AstTransform<Tree>> visitClassDecl(Tr.ClassDecl classDecl) {
        List<AstTransform<Tree>> changes = super.visitClassDecl(classDecl);
        changes.addAll(transformName(classDecl, classDecl.getExtends(), Tr.ClassDecl::withExtendings));
        changes.addAll(transformNames(classDecl, classDecl.getImplements(), Tr.ClassDecl::withImplementings));
        return changes;
    }

    @Override
    public List<AstTransform<Tree>> visitFieldAccess(Tr.FieldAccess fieldAccess) {
        List<AstTransform<Tree>> changes = super.visitFieldAccess(fieldAccess);
        changes.addAll(transformName(fieldAccess, fieldAccess.asClassReference(), Tr.FieldAccess::withTarget));
        return changes;
    }

    @Override
    public List<AstTransform<Tree>> visitMethod(Tr.MethodDecl method) {
        List<AstTransform<Tree>> changes = super.visitMethod(method);
        changes.addAll(transformName(method, method.getReturnTypeExpr(), Tr.MethodDecl::withReturnTypeExpr));
        if (method.getThrows() != null) {
            changes.addAll(transformNames(method, method.getThrows().getExceptions(), (m, exceptions) -> m.getThrows() == null ?
                    m.withThrowz(new Tr.MethodDecl.Throws(randomId(), exceptions, Formatting.format(" "))) :
                    m.withThrowz(m.getThrows().withExceptions(exceptions))));
        }
        return changes;
    }

    @Override
    public List<AstTransform<Tree>> visitMethodInvocation(Tr.MethodInvocation method) {
        List<AstTransform<Tree>> changes = super.visitMethodInvocation(method);

        if (method.getSelect() instanceof NameTree && method.getType() != null && method.getType().hasFlags(Flag.Static)) {
            changes.addAll(transformName(method, method.getSelect(), Tr.MethodInvocation::withSelect));
        }

        if (method.getTypeParameters() != null) {
            for (Tr.TypeParameter param : method.getTypeParameters().getParams()) {
                changes.addAll(transformName(param, param.getName(), Tr.TypeParameter::withName));
            }
        }

        return changes;
    }

    @Override
    public List<AstTransform<Tree>> visitMultiCatch(Tr.MultiCatch multiCatch) {
        List<AstTransform<Tree>> changes = super.visitMultiCatch(multiCatch);
        changes.addAll(transformNames(multiCatch, multiCatch.getAlternatives(), Tr.MultiCatch::withAlternatives));
        return changes;
    }

    @Override
    public List<AstTransform<Tree>> visitMultiVariable(Tr.VariableDecls multiVariable) {
        List<AstTransform<Tree>> changes = super.visitMultiVariable(multiVariable);

        if (multiVariable.getTypeExpr() instanceof Tr.MultiCatch) {
            return changes;
        }

        changes.addAll(transformName(multiVariable, multiVariable.getTypeExpr(), Tr.VariableDecls::withTypeExpr));

        List<Tr.VariableDecls.NamedVar> vars = multiVariable.getVars();
        for (int i = 0; i < vars.size(); i++) {
            Tr.VariableDecls.NamedVar var = vars.get(i);
            final int innerI = i;
            changes.addAll(transformName(multiVariable, var, (m, transformedName) -> {
                List<Tr.VariableDecls.NamedVar> transformedVars = new ArrayList<>(vars.size());
                for (int j = 0; j < vars.size(); j++) {
                    transformedVars.add(innerI == j ? var.withName(transformedName) : var);
                }
                return m.withVars(transformedVars);
            }));
        }

        return changes;
    }

    @Override
    public List<AstTransform<Tree>> visitNewArray(Tr.NewArray newArray) {
        List<AstTransform<Tree>> changes = super.visitNewArray(newArray);
        changes.addAll(transformName(newArray, newArray.getTypeExpr(), Tr.NewArray::withTypeExpr));
        return changes;
    }

    @Override
    public List<AstTransform<Tree>> visitNewClass(Tr.NewClass newClass) {
        List<AstTransform<Tree>> changes = super.visitNewClass(newClass);
        changes.addAll(transformName(newClass, newClass.getClazz(), Tr.NewClass::withClazz));
        return changes;
    }

    @Override
    public List<AstTransform<Tree>> visitParameterizedType(Tr.ParameterizedType type) {
        List<AstTransform<Tree>> changes = super.visitParameterizedType(type);
        changes.addAll(transformName(type, type.getClazz(), Tr.ParameterizedType::withClazz));
        if (type.getTypeArguments() != null) {
            changes.addAll(transformNames(type, type.getTypeArguments().getArgs(), (t, args) -> t.getTypeArguments() == null ?
                    t.withTypeArguments(new Tr.ParameterizedType.TypeArguments(randomId(), args, Formatting.EMPTY)) :
                    t.withTypeArguments(type.getTypeArguments().withArgs(args))));
        }
        return changes;
    }

    @Override
    public List<AstTransform<Tree>> visitTypeCast(Tr.TypeCast typeCast) {
        List<AstTransform<Tree>> changes = super.visitTypeCast(typeCast);
        changes.addAll(transformName(typeCast, typeCast.getClazz().getTree(),
                (t, name) -> t.withClazz(typeCast.getClazz().withTree(name))));
        return changes;
    }

    @Override
    public List<AstTransform<Tree>> visitTypeParameter(Tr.TypeParameter typeParam) {
        List<AstTransform<Tree>> changes = super.visitTypeParameter(typeParam);
        if (typeParam.getBounds() != null) {
            changes.addAll(transformNames(typeParam, typeParam.getBounds().getTypes(), (t, types) -> t.getBounds() == null ?
                    t.withBounds(new Tr.TypeParameter.Bounds(randomId(), types, Formatting.EMPTY)) :
                    t.withBounds(typeParam.getBounds().withTypes(types))));
        }
        return changes;
    }

    @Override
    public List<AstTransform<Tree>> visitWildcard(Tr.Wildcard wildcard) {
        List<AstTransform<Tree>> changes = super.visitWildcard(wildcard);
        changes.addAll(transformName(wildcard, wildcard.getBoundedType(), Tr.Wildcard::withBoundedType));
        return changes;
    }

    private <T extends Tree> List<AstTransform<Tree>> transformName(T containsName, @Nullable Tree nameField, BiFunction<T, Tr.Ident, T> change) {
        if (nameField instanceof NameTree) {
            Type.Class nameTreeClass = TypeUtils.asClass(((NameTree) nameField).getType());
            if (nameTreeClass != null && nameTreeClass.getFullyQualifiedName().equals(from)) {
                return transform(containsName, t -> change.apply(t,
                        Tr.Ident.build(randomId(), toClassType.getClassName(), toClassType, nameField.getFormatting())));
            }
        }
        return emptyList();
    }

    @SuppressWarnings("unchecked")
    private <T extends Tree, U extends Tree> List<AstTransform<Tree>> transformNames(T containsName, @Nullable Iterable<U> nodes, BiFunction<T, List<U>, Tree> change) {
        if (nodes == null) {
            return emptyList();
        }

        boolean atLeastOneChanged = false;
        List<U> transformed = new ArrayList<>();
        for (U node : nodes) {
            if (node instanceof NameTree) {
                Type.Class nodeTypeAsClass = TypeUtils.asClass(((NameTree) node).getType());
                if (nodeTypeAsClass != null && nodeTypeAsClass.getFullyQualifiedName().equals(from)) {
                    atLeastOneChanged = true;
                    transformed.add((U) Tr.Ident.build(randomId(), toClassType.getClassName(), toClassType, node.getFormatting()));
                    continue;
                }
            }
            transformed.add(node);
        }

        return atLeastOneChanged ? transform(containsName, t -> (T) change.apply(t, transformed)) : emptyList();
    }
}

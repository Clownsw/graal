/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.profdiff.matching.tree;

import org.graalvm.profdiff.core.OptimizationContextTreeNode;

/**
 * Provides an equality test of two {@link OptimizationContextTreeNode} and determines the costs of
 * edit operations. Delegates to {@link InliningTreeEditPolicy} and
 * {@link OptimizationTreeEditPolicy} for two nodes backed by nodes of the same kind.
 */
public class OptimizationContextTreeEditPolicy extends TreeEditPolicy<OptimizationContextTreeNode> {

    private final InliningTreeEditPolicy inliningTreeEditPolicy = new InliningTreeEditPolicy();

    private final OptimizationTreeEditPolicy optimizationTreeEditPolicy = new OptimizationTreeEditPolicy();

    @Override
    public boolean nodesEqual(OptimizationContextTreeNode node1, OptimizationContextTreeNode node2) {
        if (node1.getOriginalOptimization() != null && node2.getOriginalOptimization() != null) {
            return optimizationTreeEditPolicy.nodesEqual(node1.getOriginalOptimization(), node2.getOriginalOptimization());
        } else if (node1.getOriginalInliningTreeNode() != null && node2.getOriginalInliningTreeNode() != null) {
            return inliningTreeEditPolicy.nodesEqual(node1.getOriginalInliningTreeNode(), node2.getOriginalInliningTreeNode());
        }
        return node1.isRoot() && node2.isRoot();
    }

    @Override
    public long relabelCost(OptimizationContextTreeNode node1, OptimizationContextTreeNode node2) {
        assert node1 != node2;
        if (node1.getOriginalOptimization() != null && node2.getOriginalOptimization() != null) {
            return optimizationTreeEditPolicy.relabelCost(node1.getOriginalOptimization(), node2.getOriginalOptimization());
        } else if (node1.getOriginalInliningTreeNode() != null && node2.getOriginalInliningTreeNode() != null) {
            return inliningTreeEditPolicy.relabelCost(node1.getOriginalInliningTreeNode(), node2.getOriginalInliningTreeNode());
        }
        return INFINITE_COST;
    }
}

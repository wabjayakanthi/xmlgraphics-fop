/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* $Id$ */

package org.apache.fop.layoutmgr;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.fop.area.Area;
import org.apache.fop.area.BlockParent;
import org.apache.fop.fo.pagination.Flow;
import org.apache.fop.layoutmgr.inline.InlineLevelLayoutManager;

/**
 * LayoutManager for an fo:flow object.
 * Its parent LM is the PageSequenceLayoutManager.
 * This LM is responsible for getting columns of the appropriate size
 * and filling them with block-level areas generated by its children.
 * @todo Reintroduce emergency counter (generate error to avoid endless loop)
 */
public class FlowLayoutManager extends BlockStackingLayoutManager
                               implements BlockLevelLayoutManager {

    /**
     * logging instance
     */
    private static Log log = LogFactory.getLog(FlowLayoutManager.class);
    
    /** Array of areas currently being filled stored by area class */
    private BlockParent[] currentAreas = new BlockParent[Area.CLASS_MAX];

    private int currentSpan = EN_NONE;
    
    /**
     * This is the top level layout manager.
     * It is created by the PageSequence FO.
     * @param pslm parent PageSequenceLayoutManager object
     * @param node Flow object
     */
    public FlowLayoutManager(PageSequenceLayoutManager pslm, Flow node) {
        super(node);
        setParent(pslm);
    }

    /** {@inheritDoc} */
    public LinkedList getNextKnuthElements(LayoutContext context, int alignment) {

        // set layout dimensions
        int flowIPD = getCurrentPV().getCurrentSpan().getColumnWidth();
        int flowBPD = (int) getCurrentPV().getBodyRegion().getBPD();

        // currently active LM
        LayoutManager curLM;
        LinkedList returnedList;
        LinkedList returnList = new LinkedList();

        while ((curLM = getChildLM()) != null) {
            if (curLM instanceof InlineLevelLayoutManager) {
                log.error("inline area not allowed under flow - ignoring");
                curLM.setFinished(true);
                continue;
            }

            int span = EN_NONE;
            if (curLM instanceof BlockLayoutManager) {
                span = ((BlockLayoutManager)curLM).getBlockFO().getSpan();
            } else if (curLM instanceof BlockContainerLayoutManager) {
                span = ((BlockContainerLayoutManager)curLM).getBlockContainerFO().getSpan();
            }
            if (currentSpan != span) {
                log.debug("span change from " + currentSpan + " to " + span);
                context.signalSpanChange(span);
                currentSpan = span;
                SpaceResolver.resolveElementList(returnList);
                return returnList;
            }
            
            // Set up a LayoutContext
            //MinOptMax bpd = context.getStackLimit();

            LayoutContext childLC = new LayoutContext(0);
            childLC.setStackLimitBP(context.getStackLimitBP());
            childLC.setRefIPD(context.getRefIPD());
            childLC.setWritingMode(getCurrentPage().getSimplePageMaster().getWritingMode());
            
            // get elements from curLM
            returnedList = curLM.getNextKnuthElements(childLC, alignment);
            //log.debug("FLM.getNextKnuthElements> returnedList.size() = " + returnedList.size());
            if (returnList.size() == 0 && childLC.isKeepWithPreviousPending()) {
                context.setFlags(LayoutContext.KEEP_WITH_PREVIOUS_PENDING);
                childLC.setFlags(LayoutContext.KEEP_WITH_PREVIOUS_PENDING, false);
            }

            // "wrap" the Position inside each element
            LinkedList tempList = returnedList;
            returnedList = new LinkedList();
            wrapPositionElements(tempList, returnedList);

            if (returnedList.size() == 1
                && ElementListUtils.endsWithForcedBreak(returnedList)) {
                // a descendant of this flow has break-before
                returnList.addAll(returnedList);
                SpaceResolver.resolveElementList(returnList);
                return returnList;
            } else {
                if (returnList.size() > 0) {
                    // there is a block before this one
                    if (context.isKeepWithNextPending()
                            || childLC.isKeepWithPreviousPending()) {
                        //Clear pending keep flag
                        context.setFlags(LayoutContext.KEEP_WITH_NEXT_PENDING, false);
                        childLC.setFlags(LayoutContext.KEEP_WITH_PREVIOUS_PENDING, false);
                        // add an infinite penalty to forbid a break between blocks
                        returnList.add(new BreakElement(
                                new Position(this), KnuthElement.INFINITE, context));
                    } else if (!((ListElement) returnList.getLast()).isGlue()) {
                        // add a null penalty to allow a break between blocks
                        returnList.add(new BreakElement(
                                new Position(this), 0, context));
                    }
                }
                if (returnedList.size() > 0) {
                    returnList.addAll(returnedList);
                    if (ElementListUtils.endsWithForcedBreak(returnList)) {
                        if (curLM.isFinished() && !hasNextChildLM()) {
                            //If the layout manager is finished at this point, the pending
                            //marks become irrelevant.
                            childLC.clearPendingMarks();
                            //setFinished(true);
                            break;
                        }
                        // a descendant of this flow has break-after
                        SpaceResolver.resolveElementList(returnList);
                        return returnList;
                    }
                }
            }
            if (childLC.isKeepWithNextPending()) {
                //Clear and propagate
                childLC.setFlags(LayoutContext.KEEP_WITH_NEXT_PENDING, false);
                context.setFlags(LayoutContext.KEEP_WITH_NEXT_PENDING);
            }
        }

        SpaceResolver.resolveElementList(returnList);
        setFinished(true);

        if (returnList.size() > 0) {
            return returnList;
        } else {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    public int negotiateBPDAdjustment(int adj, KnuthElement lastElement) {
        log.debug(" FLM.negotiateBPDAdjustment> " + adj);

        if (lastElement.getPosition() instanceof NonLeafPosition) {
            // this element was not created by this FlowLM
            NonLeafPosition savedPos = (NonLeafPosition)lastElement.getPosition();
            lastElement.setPosition(savedPos.getPosition());
            int returnValue = ((BlockLevelLayoutManager)lastElement.getLayoutManager())
                    .negotiateBPDAdjustment(adj, lastElement);
            lastElement.setPosition(savedPos);
            log.debug(" FLM.negotiateBPDAdjustment> result " + returnValue);
            return returnValue;
        } else {
            return 0;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void discardSpace(KnuthGlue spaceGlue) {
        log.debug(" FLM.discardSpace> ");

        if (spaceGlue.getPosition() instanceof NonLeafPosition) {
            // this element was not created by this FlowLM
            NonLeafPosition savedPos = (NonLeafPosition)spaceGlue.getPosition();
            spaceGlue.setPosition(savedPos.getPosition());
            ((BlockLevelLayoutManager) spaceGlue.getLayoutManager()).discardSpace(spaceGlue);
            spaceGlue.setPosition(savedPos);
        }
    }

    /** {@inheritDoc} */
    public boolean mustKeepTogether() {
        return false;
    }

    /** {@inheritDoc} */
    public boolean mustKeepWithPrevious() {
        return false;
    }

    /** {@inheritDoc} */
    public boolean mustKeepWithNext() {
        return false;
    }

    /**
     * {@inheritDoc} 
     */
    public LinkedList getChangedKnuthElements(List oldList, /*int flaggedPenalty,*/ int alignment) {
        ListIterator oldListIterator = oldList.listIterator();
        KnuthElement returnedElement;
        LinkedList returnedList = new LinkedList();
        LinkedList returnList = new LinkedList();
        KnuthElement prevElement = null;
        KnuthElement currElement = null;
        int fromIndex = 0;

        // "unwrap" the Positions stored in the elements
        KnuthElement oldElement;
        while (oldListIterator.hasNext()) {
            oldElement = (KnuthElement)oldListIterator.next();
            if (oldElement.getPosition() instanceof NonLeafPosition) {
                // oldElement was created by a descendant of this FlowLM
                oldElement.setPosition(((NonLeafPosition)oldElement.getPosition()).getPosition());
            } else {
                // thisElement was created by this FlowLM, remove it
                oldListIterator.remove();
            }
        }
        // reset the iterator
        oldListIterator = oldList.listIterator();


        while (oldListIterator.hasNext()) {
            currElement = (KnuthElement) oldListIterator.next();
            if (prevElement != null
                && prevElement.getLayoutManager() != currElement.getLayoutManager()) {
                // prevElement is the last element generated by the same LM
                BlockLevelLayoutManager prevLM = (BlockLevelLayoutManager)
                                                 prevElement.getLayoutManager();
                BlockLevelLayoutManager currLM = (BlockLevelLayoutManager)
                                                 currElement.getLayoutManager();
                returnedList.addAll(prevLM.getChangedKnuthElements(
                        oldList.subList(fromIndex, oldListIterator.previousIndex()), alignment));
                fromIndex = oldListIterator.previousIndex();

                // there is another block after this one
                if (prevLM.mustKeepWithNext()
                    || currLM.mustKeepWithPrevious()) {
                    // add an infinite penalty to forbid a break between blocks
                    returnedList.add(new KnuthPenalty(0, KnuthElement.INFINITE, false, 
                            new Position(this), false));
                } else if (!((KnuthElement) returnedList.getLast()).isGlue()) {
                    // add a null penalty to allow a break between blocks
                    returnedList.add(new KnuthPenalty(0, 0, false, new Position(this), false));
                }
            }
            prevElement = currElement;
        }
        if (currElement != null) {
            BlockLevelLayoutManager currLM = (BlockLevelLayoutManager)
                                             currElement.getLayoutManager();
            returnedList.addAll(currLM.getChangedKnuthElements(
                    oldList.subList(fromIndex, oldList.size()), alignment));
        }

        // "wrap" the Position stored in each element of returnedList
        // and add elements to returnList
        ListIterator listIter = returnedList.listIterator();
        while (listIter.hasNext()) {
            returnedElement = (KnuthElement)listIter.next();
            if (returnedElement.getLayoutManager() != this) {
                returnedElement.setPosition(
                        new NonLeafPosition(this, returnedElement.getPosition()));
            }
            returnList.add(returnedElement);
        }

        return returnList;
    }

    /**
     * {@inheritDoc} 
     */
    public void addAreas(PositionIterator parentIter, LayoutContext layoutContext) {
        AreaAdditionUtil.addAreas(this, parentIter, layoutContext);
        flush();
    }

    /**
     * Add child area to a the correct container, depending on its
     * area class. A Flow can fill at most one area container of any class
     * at any one time. The actual work is done by BlockStackingLM.
     * 
     * @param childArea the area to add
     */
    public void addChildArea(Area childArea) {
        getParentArea(childArea);
        addChildToArea(childArea,
                          this.currentAreas[childArea.getAreaClass()]);
    }

    /**
     * {@inheritDoc}
     */
    public Area getParentArea(Area childArea) {
        BlockParent parentArea = null;
        int aclass = childArea.getAreaClass();
        
        if (aclass == Area.CLASS_NORMAL) {
            parentArea = getCurrentPV().getCurrentFlow();
        } else if (aclass == Area.CLASS_BEFORE_FLOAT) {
            parentArea = getCurrentPV().getBodyRegion().getBeforeFloat();
        } else if (aclass == Area.CLASS_FOOTNOTE) {
            parentArea = getCurrentPV().getBodyRegion().getFootnote();
        } else {
            throw new IllegalStateException("(internal error) Invalid "
                    + "area class (" + aclass + ") requested.");
        }
        
        this.currentAreas[aclass] = parentArea;
        setCurrentArea(parentArea);
        return parentArea;
    }

    /**
     * Returns the IPD of the content area
     * @return the IPD of the content area
     */
    public int getContentAreaIPD() {
        return getCurrentPV().getCurrentSpan().getColumnWidth();
    }
   
    /**
     * Returns the BPD of the content area
     * @return the BPD of the content area
     */
    public int getContentAreaBPD() {
        return (int) getCurrentPV().getBodyRegion().getBPD();
    }
    
}


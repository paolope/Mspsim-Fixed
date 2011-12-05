/**
 * Copyright (c) 2010, Swedish Institute of Computer Science.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 * This file is part of MSPSim.
 *
 * -----------------------------------------------------------------
 *
 * DwarfReader
 *
 * Author  : Joakim Eriksson
 * Created : Sun Oct 21 22:00:00 2007
 * Updated : $Date: 2010-07-09 23:22:13 +0200 (Fri, 09 Jul 2010) $
 *           $Revision: 717 $
 */

package se.sics.mspsim.debug;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import se.sics.mspsim.util.DebugInfo;
import se.sics.mspsim.util.ELFDebug;
import se.sics.mspsim.util.Utils;
import se.sics.mspsim.util.ELF;
import se.sics.mspsim.util.ELFSection;

public class DwarfReader implements ELFDebug {

    public static final boolean DEBUG = false;
    
    /* Operands for lines */
    public static final int    DW_LNS_EXT = 0;
    
    public static final int    DW_LNS_copy = 1;
    public static final int    DW_LNS_advance_pc = 2;
    public static final int    DW_LNS_advance_line = 3;
    public static final int    DW_LNS_set_file = 4;
    public static final int    DW_LNS_set_column = 5;
    public static final int    DW_LNS_negate_stmt = 6;
    public static final int    DW_LNS_set_basic_block = 7;
    public static final int    DW_LNS_const_add_pc = 8;
    public static final int    DW_LNS_fixed_advance_pc = 9;

    /* Extended operands (preceded by DW_LNS_EXT + len) */
    public static final int    DW_LNE_end_sequence = 1;
    public static final int    DW_LNE_set_address = 2;    
    public static final int    DW_LNE_define_file = 3;
    
    ELF elfFile;

    /* Address ranges */
    class Arange {
        int length;
        int version;
        int offset;
        int addressSize;
        int segmentSize;
    }

    class LineEntry {
        int address;
        int line;
        LineEntry(int line, int adr) {
            this.line = line;
            address = adr;
        }
    }
    /* Line number lookup data */
    class LineData {
        String[] includeDirs;
        String[] sourceFiles;
        LineEntry[] lineEntries;
    }

    ArrayList<LineData> lineInfo = new ArrayList<LineData>();
    
    /* some state for the line number handling */
    private int lineAddress;
    private int lineFile;
    private int lineLine;
    private int lineColumn;
    private boolean isBasicBlock = false;
    private boolean isStatement = false;
    private boolean endSequence = false;
    
    private ArrayList<Arange> aranges = new ArrayList<Arange>();
    
    public DwarfReader(ELF elfFile) {
        this.elfFile = elfFile;
    }

    public void read() {
        for (int i = 0; i < elfFile.getSectionCount(); i++) {
            ELFSection sec = elfFile.getSection(i);
            String name = sec.getSectionName();
            if (DEBUG) System.out.println("DWARF Section: " + name);
            if (".debug_aranges".equals(name)) {
                readAranges(sec);
            } else if (".debug_line".equals(name)) {
                readLines(sec);
            }
        }
    }

    private void readLines(ELFSection sec) {
        if (DEBUG) {
            System.out.println("DWARF Line - ELF Section length: " + sec.getSize());
        }
        sec.reset();
        int endPos = 0;
        ArrayList<LineEntry> lineData = new ArrayList<LineEntry>();
        while (sec.getPosition() < sec.getSize()) {
            /* here starts the reading of one file's (?) debug info */
            int totLen = sec.readElf32();
            int version = sec.readElf16();
            int proLen = sec.readElf32();
            int minOpLen = sec.readElf8();

            int defaultIsStmt = sec.readElf8();
            int lineBase = sec.readElf8();
            int lineRange = sec.readElf8();
            int opcodeBase = sec.readElf8();

            endPos += 4 + totLen;
            if (DEBUG) {
                System.out.println("Line total length: " + totLen + " endPos: " + endPos);
                System.out.println("Line pro length: " + proLen);
                System.out.println("Line version: " + version);
            }

            if (lineBase > 127) {
                lineBase = lineBase - 256;
            }
            if (DEBUG) {
                System.out.println("Line base  : " + lineBase);
                System.out.println("Line range : " + lineRange);
                System.out.println("Line - Opcode base: " + opcodeBase);
            }

            /* first char of includes (skip opcode lens)... */
            for (int i = 0; i < opcodeBase - 1; i++) {
                sec.readElf8();
            }

            //        pos = pos + 15 + opcodeBase - 1;
            //        System.out.println("Line pos = " + pos + " sec-pos = " + sec.getPosition());
            if (DEBUG) System.out.println("Line --- include files ---");
            ArrayList<String> directories = new ArrayList<String>();
            directories.add("./");
            ArrayList<String> files = new ArrayList<String>();
            StringBuilder sb = new StringBuilder();

            /* if first char is zero => no more include directories... */
            int c;
            while ((c = sec.readElf8()) != 0) {
                sb.append((char)c);
                while((c = sec.readElf8()) != 0) sb.append((char) c);
                if (DEBUG) System.out.println("Line: include file: " + sb.toString());
                directories.add(sb.toString());
                sb.setLength(0);
            }

            if (DEBUG) System.out.println("Line --- source files ---");
            long dirIndex = 0;
            long time = 0;
            long size = 0;
            while ((c = sec.readElf8()) != 0) {
                sb.append((char)c);
                while((c = sec.readElf8()) != 0) sb.append((char) c);
                dirIndex = sec.readLEB128();
                time = sec.readLEB128();
                size = sec.readLEB128();

                if (DEBUG) System.out.println("Line: source file: " + sb.toString() + "  dir: " + dirIndex + " size: " + size);
                files.add(directories.get((int) dirIndex) + "/" + sb.toString());
                sb.setLength(0);
            }

            /* Now we should have entered the position of the "code" for generating the
             * line <=> address table
             */
            if (DEBUG) {
                System.out.println("Line: position: " + sec.getPosition());
                System.out.println("Line: first bytes of the machine: ");
                System.out.print("Line: ");
            }

            while (sec.getPosition() < endPos) {
                /* reset the "state" of the state machine (6.2.2 spec) */
                lineAddress = 0;
                lineFile = 1;
                lineLine = 1;
                lineColumn = 0;
                endSequence = false;
                isStatement = defaultIsStmt != 0;
                isBasicBlock = false;

                lineData.clear();
                
                while(!endSequence) {
                    int ins =  sec.readElf8();
                    if (DEBUG) System.out.print(Utils.hex8(ins) + " ");
                    switch(ins) {
                    case DW_LNS_EXT:
                        /* extended instruction */
                        int len = sec.readElf8();
                        int extIns = sec.readElf8();
                        switch(extIns) {
                        case DW_LNE_end_sequence:
                            endSequence = true;
                            if (DEBUG) System.out.println("Line: End sequence executed!!!");
                            break;
                        case DW_LNE_define_file:
                            if (DEBUG) System.out.println("Line: Should define a file!!!!");
                            break;
                        case DW_LNE_set_address:
                            if (len == 2)
                                lineAddress = sec.readElf8();
                            if (len == 3)
                                lineAddress = sec.readElf16();
                            if (len == 5)
                                lineAddress = sec.readElf32();
                            if (DEBUG) System.out.println("Line: Set address to: " + Utils.hex16(lineAddress) +
                                    " (len: " + len + ")");
                            break;
                        }
                        break;
                    case DW_LNS_copy:
                        /* copy data to matrix... */
                        lineData.add(new LineEntry(lineLine, lineAddress));
                        isBasicBlock = false;
                        break;
                    case DW_LNS_advance_pc:
                        long add = sec.readLEB128();
                        lineAddress += add * minOpLen;
                        if (DEBUG) System.out.println("Line: Increased address to: " + Utils.hex16(lineAddress));
                        break;
                    case DW_LNS_advance_line:
                        long addLine = sec.readLEB128S();
                        lineLine += addLine;
                        if (DEBUG) System.out.println("Line: Increased line to: " + lineLine +
                                " (incr: " + addLine + ")");
                        break;
                    case DW_LNS_set_file:
                        lineFile = (int) sec.readLEB128();
                        if (DEBUG) System.out.println("Line: Set file to: " + lineFile);
                        break;
                    case DW_LNS_set_column:
                        lineColumn = (int) sec.readLEB128();
                        if (DEBUG) System.out.println("Line: set column to: " + lineColumn);
                        break;
                    case DW_LNS_negate_stmt:
                        isStatement = !isStatement;
                        if (DEBUG) System.out.println("Line: Negated is statement");
                        break;
                    case DW_LNS_set_basic_block:
                        isBasicBlock = true;
                        if (DEBUG) System.out.println("Line: Set basic block to true");
                        break;
                    case DW_LNS_const_add_pc:
                        if (DEBUG) System.out.println("Line: *** Should add const to PC - but how much - same as FF??");
                        break;
                    case DW_LNS_fixed_advance_pc:
                        int incr = sec.readElf16();
                        lineAddress += incr;
                        if (DEBUG) System.out.println("Line: *** Increased address to: " + Utils.hex16(lineAddress));
                        break;
                    default:
                        int lineInc = lineBase + ((ins - opcodeBase) % lineRange);
                        int addrInc = (ins - opcodeBase) / lineRange;
                        lineAddress += addrInc * minOpLen;
                        lineLine += lineInc;
                        lineData.add(new LineEntry(lineLine, lineAddress));
                        isBasicBlock = false;
                        
                        if (DEBUG) System.out.println("Line: *** Special operation => addr: " +
                                Utils.hex16(lineAddress) + " Line: " + lineLine + " lineInc: " + lineInc);
                    }
                }
                if (DEBUG) System.out.println("Line - Position " + sec.getPosition() + " totLen: " + totLen);

                if (lineData.size() > 0) {
                    /* create a block of line-address data that can be used for lookup later.*/
                    LineData lineTable = new LineData();
                    lineTable.lineEntries = lineData.toArray(new LineEntry[0]);
                    lineTable.includeDirs = directories.toArray(new String[0]);
                    lineTable.sourceFiles = files.toArray(new String[0]);
                    lineInfo.add(lineTable);
                }
            }
        }
        
        /* Now we have some tables of data where it should be possible to sort out which
         * addresses correspond to which lines!?
         */
        
        
        if (DEBUG) {
            for (LineData data : lineInfo) {
                System.out.println("Compiled file: " + data.sourceFiles[0]);
                System.out.println("Start address: " +
                        Utils.hex16(data.lineEntries[0].address));
                System.out.println("End  address: " +
                        Utils.hex16(data.lineEntries[data.lineEntries.length - 1].address));
                System.out.println("Size: " +
                        Utils.hex16(data.lineEntries[data.lineEntries.length - 1].address - data.lineEntries[0].address));
            }
        }
    }
        
    /* DWARF - address ranges information */
    private void readAranges(ELFSection sec) {
        if (DEBUG) System.out.println("DWARF Aranges - ELF Section length: " + sec.getSize());
        int pos = 0;
        int index = 0;
        do {
            Arange arange = new Arange();
            /* here we should read the address data */
            arange.length = sec.readElf32(pos + 0); /* length not including the length field */
            arange.version = sec.readElf16(pos + 4); /* version */
            arange.offset = sec.readElf32(pos + 6); /* 4 byte offset into debug_info section (?)*/
            arange.addressSize = sec.readElf8(pos + 10); /* size of address */
            arange.segmentSize = sec.readElf8(pos + 11); /* size of segment descriptor */
            if (DEBUG) {
                System.out.println("DWARF: aranges no " + index);
                System.out.println("DWARF: Length: " + arange.length);
                System.out.println("DWARF: Version: " + arange.version);
                System.out.println("DWARF: Offset: " + arange.offset);
                System.out.println("DWARF: Address size: " + arange.addressSize);
            }

            index++;
            pos += 12;
            if (arange.addressSize == 2) {
                /* these needs to be added too! */
                int addr, len;
                do {
                    addr = sec.readElf16(pos);
                    len = sec.readElf16(pos + 2);
                    pos += 4;
                    if (DEBUG) System.out.println("DWARF: ($" + Utils.hex16(addr) + "," + len + ")");
                } while (addr != 0 || len != 0);
            }
        } while (pos < sec.getSize());
    }

    /* Access methods for data... */
    public DebugInfo getDebugInfo(int address) {
    	DebugInfo dbgInfo = null;
//		try {
    	for (int i = 0; i < lineInfo.size(); i++) {
		    LineData data = lineInfo.get(i);
		    int start = data.lineEntries[0].address;
		    int end = data.lineEntries[data.lineEntries.length - 1].address;
		    if (address <= end && address >= start) {
		        for (int j = data.lineEntries.length -1 ; j >= 0; j--) {
		            if (data.lineEntries[j].address <= address) {
		            	    int lastSlash = data.sourceFiles[0].lastIndexOf("/");
							String path = (lastSlash == -1) ? "" : data.sourceFiles[0].substring(0,lastSlash);
							String file = (lastSlash == -1) ? data.sourceFiles[0] : data.sourceFiles[0].substring(lastSlash+1);
							System.out.println(path);
							System.out.println(file);
		                    dbgInfo = new DebugInfo(data.lineEntries[j].line, path, file, "* not available");
		                    break;
		            }
		        }
		    }
		}
	//	} catch (IOException e) {
	//		System.out.println("Could not get canonical file name");
	//	}
    	return dbgInfo;
    }

    public ArrayList<Integer> getExecutableAddresses() {
        ArrayList<Integer> executableAddresses = new ArrayList<Integer>();
        for (LineData data: lineInfo) {
        	for (LineEntry entry: data.lineEntries) {
        		executableAddresses.add(entry.address);
        	}
        }
    	return executableAddresses;
    }

    public String[] getSourceFiles() {
        String[] sourceFilesArray = new String[lineInfo.size()];
        for (int i = 0; i < lineInfo.size(); i++) {
				sourceFilesArray[i] = lineInfo.get(i).sourceFiles[0];
        }
        return sourceFilesArray;
    }
}

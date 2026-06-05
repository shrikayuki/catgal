package com.catgal.common.utils;

import java.util.BitSet;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 简易布隆过滤器 (学习原理)
 */
public class BloomFilterUtil {
    private final int bitSize;
    private final BitSet bitSet;
    private final int hashCount;
    private static int[] SEED = {2,3,5,7,11,13,17,19,23,29};




    public BloomFilterUtil() {
        this.bitSize = 1024;
        this.bitSet = new BitSet(bitSize);
        this.hashCount = 3;
    }

    public BloomFilterUtil(int bitSize, int hashCount) {
        this.bitSize = bitSize;
        this.bitSet = new BitSet(bitSize);
        this.hashCount = hashCount;
    }

    public void add(Object t){
        if(t==null){
            return;
        }
        String s = String.valueOf(t);
        for (int i = 0; i< hashCount; i++){
            int hash = hash(s, SEED[i]);
            int index = hash % bitSize;
            bitSet.set(index);
        }
    }

    public boolean mayContains(Object t) {
        if (t == null) return false;
        String s = String.valueOf(t);
        for (int i = 0; i < hashCount; i++) {
            int hash = hash(s, SEED[i]);  // 用相同的种子
            int index = Math.abs(hash) % bitSize;
            if (!bitSet.get(index)) return false;
        }
        return true;
    }

    private int hash(String s, int seed){
        int hash = 0;
        for (int i = 0; i<s.length(); i++){
            char c = s.charAt(i);
            hash = hash*seed+c;
        }
        return hash&Integer.MAX_VALUE;
    }



}

package com.ai.assistance.custard.api.chat.enhance

/**
 * Utility class for processing user input
 */
object InputProcessor {
    
    /**
     * Process user input with a small delay to show processing feedback
     * 
     * @param input The input text to process
     * @return The processed input text
     */
    suspend fun processUserInput(input: String): String {
        // In the future, we could add more sophisticated processing here
        return input
    }
} 
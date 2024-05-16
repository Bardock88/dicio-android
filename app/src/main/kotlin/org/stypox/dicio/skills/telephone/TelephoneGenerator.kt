package org.stypox.dicio.skills.telephone

import org.dicio.skill.chain.OutputGenerator
import org.dicio.skill.output.SkillOutput
import org.dicio.skill.standard.StandardResult
import org.stypox.dicio.Sentences_en.telephone

class TelephoneGenerator : OutputGenerator<StandardResult>() {
    override fun generate(data: StandardResult): SkillOutput {
        val contentResolver = ctx().android.contentResolver
        val userContactName = data.getCapturingGroup(telephone.who)!!.trim { it <= ' ' }
        val contacts = Contact.getFilteredSortedContacts(contentResolver, userContactName)
        val validContacts = ArrayList<Pair<String, List<String>>>()

        var i = 0
        while (validContacts.size < 5 && i < contacts.size) {
            val contact = contacts[i]
            val numbers = contact.getNumbers(contentResolver)
            if (numbers.isEmpty()) {
                ++i
                continue
            }
            if (validContacts.isEmpty()
                && contact.distance < 3
                && numbers.size == 1 // it has just one number
                && (contacts.size <= i + 1 // the next contact has a distance higher by 3+
                        || contacts[i + 1].distance - 2 > contact.distance)
            ) {
                // very close match with just one number and without distance ties: call it directly
                return ConfirmCallOutput(contact.name, numbers[0])
            }
            validContacts.add(Pair(contact.name, numbers))
            ++i
        }

        if (validContacts.size == 1 // there is exactly one valid contact and ...
            // ... either it has exactly one number, or we would be forced (because no number parser
            // is available) to use ContactChooserName, which only uses the first phone number
            // anyway
            && (validContacts[0].second.size == 1 || ctx().parserFormatter == null)
        ) {
            // not a good enough match, but since we have only this, call it directly
            val contact = validContacts[0]
            return ConfirmCallOutput(contact.first, contact.second[0])
        }

        // this point will not be reached if a very close match was found
        return TelephoneOutput(validContacts)
    }
}